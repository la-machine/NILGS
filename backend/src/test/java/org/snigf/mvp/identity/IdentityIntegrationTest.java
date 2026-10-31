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
import org.snigf.mvp.support.PostgisIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
/**
 * Shared setup of the identity integration tests: the whole application on real PostGIS
 * with MockMvc through the real filter chain and Spring Session, a demo office, and a
 * {@link Browser} that keeps cookies and echoes the CSRF token like the interface does.
 */
public abstract class IdentityIntegrationTest extends PostgisIntegrationTest {

    protected static final String SESSION_COOKIE = "__Host-snigf-session";
    protected static final String CSRF_COOKIE = "__Host-snigf-xsrf";
    protected static final String CSRF_HEADER = "X-XSRF-TOKEN";
    protected static final String PASSWORD = "Correct-Horse-Battery-42"; // gitleaks:allow (synthetic test-account password, see .gitleaksignore)


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
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordHasher hasher;

    /** A browser: keeps cookies, fetches and echoes the CSRF token, uses one client address. */
    protected final class Browser {
        public final Map<String, String> cookies = new LinkedHashMap<>();
        final String address;

        public Browser() {
            this("192.0.2." + (1 + (int) (Math.random() * 250)));
        }

        public Browser(String address) {
            this.address = address;
        }

        public MockHttpServletResponse send(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
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

        public MockHttpServletResponse csrf() throws Exception {
            return send(get("/api/v1/auth/csrf"));
        }

        public MockHttpServletResponse register(String email, String name, String password) throws Exception {
            csrf();
            return send(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                    "{\"email\":\"" + email + "\",\"displayName\":\"" + name + "\",\"password\":\"" + password
                            + "\",\"preferredLanguage\":\"fr\"}"));
        }

        public MockHttpServletResponse login(String email, String password) throws Exception {
            csrf();
            return send(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
        }

        public MockHttpServletResponse session() throws Exception {
            return send(get("/api/v1/auth/session"));
        }
    }

    protected static String email() {
        return "user-" + UUID.randomUUID() + "@demo.invalid";
    }

    protected void assertWeak(MockHttpServletResponse response, String violation) throws Exception {
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentAsString()).contains("PASSWORD_WEAK").contains(violation);
    }

    protected UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE lower(email) = lower(?)", UUID.class, email);
    }

    protected List<String> actions(UUID user) {
        return jdbc.queryForList("SELECT action FROM audit_event WHERE entity_id = ? ORDER BY chain_pos", String.class, user);
    }

    protected UUID insertStaff(String email, String role) {
        return insertStaff(email, role, "DEMOIT");
    }

    /** A staff account with {@code role} at {@code officeCode} (created if needed). */
    protected UUID insertStaff(String email, String role, String officeCode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, email, display_name, password_hash, account_type) VALUES (?, ?, ?, ?, 'STAFF')",
                id, email, "Demo Officer", hasher.hash(PASSWORD));
        // The bootstrap path (V8): only the superuser assigns a role without an approved
        // request, as the deployment procedure does for the first administrators.
        try (java.sql.Connection su = PostgisDatabase.shared().superuserConnection();
             java.sql.PreparedStatement office = su.prepareStatement("INSERT INTO office (id, code, name_fr, name_en)"
                     + " VALUES (gen_random_uuid(), ?, 'Bureau de test', 'Test office') ON CONFLICT (code) DO NOTHING");
             java.sql.PreparedStatement insert = su.prepareStatement("INSERT INTO role_assignment"
                     + " (id, user_id, role, office_id, grant_reason) SELECT ?, ?, ?, id, 'test seed' FROM office"
                     + " WHERE code = ?")) {
            office.setString(1, officeCode);
            office.executeUpdate();
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, id);
            insert.setString(3, role);
            insert.setString(4, officeCode);
            insert.executeUpdate();
            su.commit();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    protected static final String ORIGIN = "https://localhost:47000";
    protected static final String MFA = "/api/v1/auth/mfa";
    protected final JsonMapper json = JsonMapper.builder().build();

    /** An emulated FIDO2 authenticator (user verification capable) in a browser at {@code origin}. */
    protected final class Authenticator {
        public final ClientPlatform platform;
        public String credentialId;

        public Authenticator() {
            this(ORIGIN);
        }

        public Authenticator(String origin) {
            this.platform = new ClientPlatform(new Origin(origin),
                    new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
        }

        /** {@code navigator.credentials.create} with the server's options, as the registration body. */
        String create(String optionsJson, String label) {
            JsonNode o = json.readTree(optionsJson);
            List<PublicKeyCredentialParameters> params = new ArrayList<>();
            o.path("pubKeyCredParams").forEach(p -> params.add(new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.create(p.path("alg").asLong()))));
            PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                    new PublicKeyCredentialRpEntity(o.path("rp").path("id").asString(""), o.path("rp").path("name").asString("")),
                    new PublicKeyCredentialUserEntity(b64(o.path("user").path("id").asString("")),
                            o.path("user").path("name").asString(""), o.path("user").path("displayName").asString("")),
                    new DefaultChallenge(b64(o.path("challenge").asString(""))), params,
                    o.path("timeout").asLong(), descriptors(o.path("excludeCredentials")),
                    new AuthenticatorSelectionCriteria(null, ResidentKeyRequirement.DISCOURAGED,
                            UserVerificationRequirement.REQUIRED),
                    AttestationConveyancePreference.NONE, null);
            PublicKeyCredential<AuthenticatorAttestationResponse, ?> created = platform.create(options);
            credentialId = b64(created.getRawId());
            ObjectNode credential = credential(created.getRawId());
            ObjectNode response = credential.putObject("response");
            response.put("clientDataJSON", b64(created.getResponse().getClientDataJSON()));
            response.put("attestationObject", b64(created.getResponse().getAttestationObject()));
            response.putArray("transports").add("usb");
            ObjectNode body = json.createObjectNode();
            ObjectNode publicKey = body.putObject("publicKey");
            publicKey.set("credential", credential);
            publicKey.put("label", label);
            return json.writeValueAsString(body);
        }

        /** {@code navigator.credentials.get} with the server's options, as the assertion body. */
        public String get(String optionsJson) {
            return get(optionsJson, null);
        }

        /** As {@link #get(String)}, but offering {@code credentialId} whatever the server allowed. */
        public String get(String optionsJson, String forcedCredentialId) {
            JsonNode o = json.readTree(optionsJson);
            List<PublicKeyCredentialDescriptor> allowed = forcedCredentialId == null
                    ? descriptors(o.path("allowCredentials"))
                    : List.of(new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, b64(forcedCredentialId), null));
            PublicKeyCredentialRequestOptions options = new PublicKeyCredentialRequestOptions(
                    new DefaultChallenge(b64(o.path("challenge").asString(""))), o.path("timeout").asLong(),
                    o.path("rpId").asString(""), allowed, UserVerificationRequirement.REQUIRED, null);
            PublicKeyCredential<AuthenticatorAssertionResponse, ?> asserted = platform.get(options);
            ObjectNode credential = credential(asserted.getRawId());
            ObjectNode response = credential.putObject("response");
            response.put("clientDataJSON", b64(asserted.getResponse().getClientDataJSON()));
            response.put("authenticatorData", b64(asserted.getResponse().getAuthenticatorData()));
            response.put("signature", b64(asserted.getResponse().getSignature()));
            if (asserted.getResponse().getUserHandle() != null) {
                response.put("userHandle", b64(asserted.getResponse().getUserHandle()));
            }
            return json.writeValueAsString(credential);
        }

        protected ObjectNode credential(byte[] rawId) {
            ObjectNode credential = json.createObjectNode();
            credential.put("id", b64(rawId));
            credential.put("rawId", b64(rawId));
            credential.put("type", "public-key");
            credential.putObject("clientExtensionResults");
            credential.put("authenticatorAttachment", "cross-platform");
            return credential;
        }

        protected List<PublicKeyCredentialDescriptor> descriptors(JsonNode list) {
            List<PublicKeyCredentialDescriptor> descriptors = new ArrayList<>();
            if (list instanceof ArrayNode array) {
                array.forEach(d -> descriptors.add(new PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY,
                        b64(d.path("id").asString("")), null)));
            }
            return descriptors;
        }
    }

    /** A staff member with two registered authenticators and their recovery codes. */
    protected record Enrolled(String email, UUID id, Authenticator first, Authenticator second, List<String> recoveryCodes) {
    }


    // --- Helpers --------------------------------------------------------------------------

    protected Enrolled enrol() throws Exception {
        String email = email();
        UUID id = insertStaff(email, "REVIEWER");
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        Authenticator first = new Authenticator();
        Authenticator second = new Authenticator();
        register(browser, first, "Office key");
        JsonNode done = register(browser, second, "Backup key");
        List<String> codes = new ArrayList<>();
        done.path("recoveryCodes").forEach(c -> codes.add(c.asString("")));
        assertThat(codes).hasSize(10);
        logout(browser);
        return new Enrolled(email, id, first, second, codes);
    }

    protected JsonNode register(Browser browser, Authenticator authenticator, String label) throws Exception {
        String options = options(browser, "registration");
        MockHttpServletResponse response = browser.send(json(post(MFA + "/webauthn/registration"),
                authenticator.create(options, label)));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    protected MockHttpServletResponse prove(Browser browser, Authenticator authenticator) throws Exception {
        return browser.send(json(post(MFA + "/webauthn/assertion"), authenticator.get(options(browser, "assertion"))));
    }

    protected MockHttpServletResponse recover(Browser browser, String code) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        browser.csrf();   // the token is renewed at login and at every elevation
        return browser.send(json(post(MFA + "/recovery"), json.writeValueAsString(body)));
    }

    protected String options(Browser browser, String ceremony) throws Exception {
        browser.csrf();
        MockHttpServletResponse response = browser.send(post(MFA + "/webauthn/" + ceremony + "/options"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    protected void logout(Browser browser) throws Exception {
        browser.csrf();
        assertThat(browser.send(post("/api/v1/auth/logout")).getStatus()).isEqualTo(204);
    }

    protected static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static byte[] b64(String base64url) {
        return Base64.getUrlDecoder().decode(base64url);
    }

    protected static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A staff member with {@code role} at the test office, two keys enrolled, and a FULL session. */
    protected record SignedIn(UUID id, String email, Browser browser) {
    }

    protected SignedIn signedIn(String role) throws Exception {
        return signedIn(role, "DEMOIT");
    }

    /** A staff member with {@code role} at {@code officeCode}, two keys enrolled, and a FULL session. */
    protected SignedIn signedIn(String role, String officeCode) throws Exception {
        String email = email();
        UUID id = insertStaff(email, role, officeCode);
        Browser browser = new Browser();
        browser.login(email, PASSWORD);
        register(browser, new Authenticator(), "Office key");
        JsonNode done = register(browser, new Authenticator(), "Backup key");
        assertThat(done.path("nextStep").asString("")).isEqualTo("DONE");
        browser.csrf();
        return new SignedIn(id, email, browser);
    }

    /** Sends a JSON body with the CSRF token, as the interface does. */
    protected MockHttpServletResponse sendJson(Browser browser,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        browser.csrf();
        return browser.send(json(request, body));
    }
}
