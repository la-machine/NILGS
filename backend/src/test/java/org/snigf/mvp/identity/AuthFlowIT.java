// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.domain.PasswordHasher;
import org.snigf.mvp.support.PostgisDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security step 4a end to end, through the real filter chain, Spring Session in PostgreSQL
 * and the audit trail (Slice 1 design §5 and §6; P5, P6, P7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIT {

    static final String SESSION_COOKIE = "__Host-snigf-session";
    static final String CSRF_COOKIE = "__Host-snigf-xsrf";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";
    static final String PASSWORD = "Correct-Horse-Battery-42";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgisDatabase db = PostgisDatabase.shared();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.password", db::appPassword);
    }

    /** Offices are reference data the application can only read; seed one demo office. */
    @org.junit.jupiter.api.BeforeAll
    static void demoOffice() throws Exception {
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.Statement statement = su.createStatement()) {
            statement.execute("INSERT INTO office (id, code, name_fr, name_en) VALUES ("
                    + "'00000000-0000-7000-8000-0000000000a1', 'DEMOIT', 'Bureau de test', 'Test office')"
                    + " ON CONFLICT (code) DO NOTHING");
            su.commit();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordHasher hasher;

    /** A browser: keeps cookies, fetches and echoes the CSRF token, uses one client address. */
    final class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        final String address;

        Browser() {
            this("192.0.2." + (1 + (int) (Math.random() * 250)));
        }

        Browser(String address) {
            this.address = address;
        }

        MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
            request.with(r -> {
                r.setRemoteAddr(address);
                return r;
            });
            cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
            if (cookies.containsKey(CSRF_COOKIE)) {
                request.header(CSRF_HEADER, cookies.get(CSRF_COOKIE));
            }
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
                String pair = header.split(";", 2)[0];
                String name = pair.substring(0, pair.indexOf('='));
                String value = pair.substring(pair.indexOf('=') + 1);
                if (value.isEmpty() || header.contains("Max-Age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
            return response;
        }

        MockHttpServletResponse csrf() throws Exception {
            return send(get("/api/v1/auth/csrf"));
        }

        MockHttpServletResponse register(String email, String name, String password) throws Exception {
            csrf();
            return send(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                    "{\"email\":\"" + email + "\",\"displayName\":\"" + name + "\",\"password\":\"" + password
                            + "\",\"preferredLanguage\":\"fr\"}"));
        }

        MockHttpServletResponse login(String email, String password) throws Exception {
            csrf();
            return send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
        }

        MockHttpServletResponse session() throws Exception {
            return send(get("/api/v1/auth/session"));
        }
    }

    static String email() {
        return "user-" + UUID.randomUUID() + "@demo.invalid";
    }

    @Test
    void theCsrfTokenTravelsInAHostCookieAndIsRequiredEvenForLogin() throws Exception {
        Browser browser = new Browser();
        MockHttpServletResponse csrf = browser.csrf();
        assertThat(csrf.getStatus()).isEqualTo(200);
        assertThat(csrf.getContentAsString()).contains(CSRF_COOKIE).contains(CSRF_HEADER);
        jakarta.servlet.http.Cookie token = csrf.getCookie(CSRF_COOKIE);
        assertThat(token).isNotNull();
        assertThat(token.getSecure()).isTrue();
        assertThat(token.getPath()).isEqualTo("/");
        assertThat(token.getAttribute("SameSite")).isEqualTo("Strict");

        Browser noToken = new Browser();
        MockHttpServletResponse refused = noToken.send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@demo.invalid\",\"password\":\"whatever-long-1\"}"));
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("CSRF_INVALID");
    }

    @Test
    void anApplicantRegistersLogsInSeesTheSessionAndLogsOut() throws Exception {
        String email = email();
        Browser browser = new Browser();

        assertThat(browser.register(email, "Awa Demo", PASSWORD).getStatus()).isEqualTo(201);
        MockHttpServletResponse login = browser.login(email.toUpperCase(), PASSWORD);   // case-insensitive address
        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(login.getContentAsString()).contains("\"nextStep\":\"DONE\"");
        String cookie = String.join("\n", login.getHeaders(HttpHeaders.SET_COOKIE));
        assertThat(cookie).contains(SESSION_COOKIE + "=").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict").contains("Path=/");

        MockHttpServletResponse session = browser.session();
        assertThat(session.getStatus()).isEqualTo(200);
        assertThat(session.getContentAsString()).contains("\"authLevel\":\"FULL\"").contains("\"APPLICANT\"")
                .contains("\"identityVerified\":false").doesNotContain(email);

        browser.csrf();
        assertThat(browser.send(post("/api/v1/auth/logout")).getStatus()).isEqualTo(204);
        MockHttpServletResponse after = browser.session();
        assertThat(after.getStatus()).isEqualTo(401);
        assertThat(after.getContentAsString()).contains("AUTH_REQUIRED");

        UUID user = userId(email);
        assertThat(actions(user)).containsSubsequence("USER_REGISTERED", "LOGIN_SUCCEEDED", "LOGOUT");
        assertThat(jdbc.queryForObject("SELECT actor_type FROM audit_event WHERE entity_id = ? AND action = 'LOGIN_SUCCEEDED'",
                String.class, user)).as("the user is the actor of their own login").isEqualTo("USER");
    }

    @Test
    void theLoginIssuesANewSessionIdSoAPlantedSessionIsUseless() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Fixation Test", PASSWORD);
        browser.login(email, PASSWORD);
        String first = browser.cookies.get(SESSION_COOKIE);
        browser.login(email, PASSWORD);

        assertThat(browser.cookies.get(SESSION_COOKIE)).isNotEqualTo(first);
        Browser attacker = new Browser();
        attacker.cookies.put(SESSION_COOKIE, first);
        assertThat(attacker.session().getStatus()).isEqualTo(401);
    }

    @Test
    void registrationRevealsNothingAboutExistingAccounts() throws Exception {
        String email = email();
        Browser browser = new Browser();
        MockHttpServletResponse first = browser.register(email, "First Owner", PASSWORD);
        MockHttpServletResponse second = browser.register(email, "Someone Else", "Another-Strong-Pass-77");

        assertThat(second.getStatus()).isEqualTo(first.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(browser.login(email, "Another-Strong-Pass-77").getStatus()).as("not overwritten").isEqualTo(401);
        assertThat(browser.login(email, PASSWORD).getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'USER_REGISTERED'"
                + " AND outcome = 'FAILED'", Long.class, userId(email))).isEqualTo(1);
    }

    @Test
    void weakPasswordsAreRefusedWithTheirReasons() throws Exception {
        Browser browser = new Browser();
        assertWeak(browser.register(email(), "Short Pass", "Short-1"), "TOO_SHORT");
        assertWeak(browser.register(email(), "Common Pass", "Q1w2e3r4t5y6"), "COMMONLY_USED");
        assertWeak(browser.register("mballa.ndongo@demo.invalid", "Name In Pass", "Mballa-Ndongo-2026!"),
                "CONTAINS_ACCOUNT_NAME");

        MockHttpServletResponse invalid = browser.register("not-an-address", "Bad Address", PASSWORD);
        assertThat(invalid.getStatus()).isEqualTo(422);
        assertThat(invalid.getContentAsString()).contains("VALIDATION_FAILED").contains("EMAIL_INVALID");
    }

    @Test
    void everyLoginFailureLooksExactlyTheSame() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Same Answer", PASSWORD);
        UUID user = userId(email);

        MockHttpServletResponse unknown = browser.login(email(), PASSWORD);
        MockHttpServletResponse wrong = browser.login(email, "Wrong-Password-123");
        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", user);
        MockHttpServletResponse disabled = browser.login(email, PASSWORD);

        for (MockHttpServletResponse r : List.of(unknown, wrong, disabled)) {
            assertThat(r.getStatus()).isEqualTo(401);
            assertThat(r.getContentAsString()).isEqualTo(unknown.getContentAsString()).contains("AUTH_FAILED");
        }
        assertThat(jdbc.queryForList("SELECT details->>'reason' FROM audit_event WHERE entity_id = ?"
                + " AND action = 'LOGIN_FAILED' ORDER BY chain_pos", String.class, user))
                .as("the audit trail keeps the real reason").containsExactly("WRONG_PASSWORD", "DISABLED");
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Lock Test", PASSWORD);
        UUID user = userId(email);

        for (int i = 0; i < 5; i++) {
            assertThat(browser.login(email, "Wrong-Password-" + i + "x").getStatus()).isEqualTo(401);
        }
        assertThat(browser.login(email, PASSWORD).getStatus()).as("locked").isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT locked_until > now() + interval '14 minutes' FROM app_user WHERE id = ?",
                Boolean.class, user)).isTrue();
        assertThat(actions(user)).contains("ACCOUNT_LOCKED");
        assertThat(jdbc.queryForObject("SELECT details->>'reason' FROM audit_event WHERE entity_id = ?"
                + " AND action = 'LOGIN_FAILED' ORDER BY chain_pos DESC LIMIT 1", String.class, user)).isEqualTo("LOCKED");
    }

    @Test
    void passwordsAreStoredAsPepperedArgon2id() throws Exception {
        String email = email();
        new Browser().register(email, "Hash Test", PASSWORD);

        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId(email));
        assertThat(stored).startsWith("$snigf-pepper$v=1$$argon2id$v=19$").doesNotContain(PASSWORD);
    }

    @Test
    void staffStopAtTheSecondFactorAndCannotCallTheApi() throws Exception {
        String email = email();
        UUID staff = insertStaff(email, "CADASTRAL_OFFICER");
        Browser browser = new Browser();

        MockHttpServletResponse login = browser.login(email, PASSWORD);
        assertThat(login.getStatus()).isEqualTo(200);
        assertThat(login.getContentAsString()).contains("\"nextStep\":\"MFA_ENROLMENT\"");
        assertThat(browser.session().getContentAsString()).contains("\"authLevel\":\"PASSWORD\"");

        MockHttpServletResponse denied = browser.send(get("/api/v1/parcels"));
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString()).contains("ACCESS_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'ACCESS_DENIED' AND actor_id = ?",
                Long.class, staff)).isEqualTo(1);
    }

    @Test
    void disablingAnAccountEndsItsSessionsAtTheNextRequest() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Disable Test", PASSWORD);
        browser.login(email, PASSWORD);
        assertThat(browser.session().getStatus()).isEqualTo(200);

        jdbc.update("UPDATE app_user SET enabled = false WHERE id = ?", userId(email));

        assertThat(browser.session().getStatus()).isEqualTo(401);
    }

    @Test
    void loginsAreThrottledPerClientAddress() throws Exception {
        Browser flooding = new Browser("198.51.100.77");
        MockHttpServletResponse last = null;
        for (int i = 0; i < 21; i++) {
            last = flooding.login("nobody-" + i + "@demo.invalid", "Whatever-Long-Pass");
        }
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(last.getContentAsString()).contains("RATE_LIMITED");

        assertThat(new Browser("198.51.100.78").login("nobody@demo.invalid", "Whatever-Long-Pass").getStatus())
                .as("another address is not affected").isEqualTo(401);
    }

    @Test
    void theSessionStoresOnlyPlainValuesAndRefusesAnythingElse() throws Exception {
        String email = email();
        Browser browser = new Browser();
        browser.register(email, "Serial Test", PASSWORD);
        browser.login(email, PASSWORD);
        String primary = jdbc.queryForObject("SELECT primary_id FROM spring_session WHERE principal_name = ?",
                String.class, userId(email).toString());
        assertThat(jdbc.queryForList("SELECT attribute_name FROM spring_session_attributes WHERE session_primary_id = ?"
                + " ORDER BY 1", String.class, primary)).containsExactlyInAnyOrder("snigf.auth.user", "snigf.auth.level",
                "snigf.auth.at", org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);

        // Someone with write access to the table plants a serialised object graph.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(List.of("gadget")));
        }
        jdbc.update("UPDATE spring_session_attributes SET attribute_bytes = ? WHERE session_primary_id = ?"
                + " AND attribute_name = 'snigf.auth.level'", bytes.toByteArray(), primary);

        assertThat(browser.session().getStatus()).as("the planted value is refused, the session ends").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE primary_id = ?", Long.class, primary))
                .isZero();
    }

    @Test
    void anonymousApiCallsAreRefusedWithAuthRequired() throws Exception {
        MockHttpServletResponse response = new Browser().send(get("/api/v1/parcels"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_REQUIRED");
    }

    private void assertWeak(MockHttpServletResponse response, String violation) throws Exception {
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("PASSWORD_WEAK").contains(violation);
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = lower(?)", UUID.class, email);
    }

    private List<String> actions(UUID user) {
        return jdbc.queryForList("SELECT action FROM audit_event WHERE entity_id = ? ORDER BY chain_pos", String.class, user);
    }

    private UUID insertStaff(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES (?, ?, ?, ?, 'STAFF')",
                id, email, "Demo Officer", hasher.hash(PASSWORD));
        UUID office = jdbc.queryForObject("SELECT id FROM office ORDER BY code LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO role_assignment (id, user_id, role, office_id, grant_reason) VALUES (?, ?, ?, ?, 'test seed')",
                UUID.randomUUID(), id, role, office);
        return id;
    }
}
