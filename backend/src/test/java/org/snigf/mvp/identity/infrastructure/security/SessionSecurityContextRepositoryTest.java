// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.application.LoginService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Session limits P5 and authorities per authentication level. */
class SessionSecurityContextRepositoryTest {

    static final Instant LOGIN = Instant.parse("2026-10-21T08:00:00Z");
    static final UUID USER = UUID.randomUUID();

    private static IdentityProperties properties() {
        return new IdentityProperties(
                new IdentityProperties.Password(12, 128, 1024, 1, 1, 1, Duration.ofSeconds(1)),
                new IdentityProperties.Lockout(5, Duration.ofMinutes(15)),
                new IdentityProperties.Session(Duration.ofHours(8), "__Host-s", "Strict"),
                new IdentityProperties.Csrf("__Host-x", "X-XSRF-TOKEN"),
                new IdentityProperties.RateLimit(20, 10, 1000),
                new IdentityProperties.WebAuthn("localhost", "SNIGF test", java.util.List.of("https://localhost:47000"),
                        Duration.ofMinutes(2), "none", 2, 10),
                new IdentityProperties.Administration(Duration.ofDays(7)));
    }

    private static SessionSecurityContextRepository at(Instant now, LoginService logins) {
        return new SessionSecurityContextRepository(logins, properties(), Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A reviewer at DEMO1, with the permissions the REVIEWER role gives. */
    private static AuthenticatedUser reviewer(AuthLevel level) {
        return new AuthenticatedUser(USER, "Demo", AccountType.STAFF, Set.of(new org.snigf.mvp.identity.RoleGrant("REVIEWER", "DEMO1")),
                Set.of("parcel.read", "application.read", "review.recommend"), java.util.List.of(), level, LOGIN);
    }

    private static LoginService restoring(AuthLevel level) {
        LoginService logins = mock(LoginService.class);
        when(logins.restore(any(), any(), any())).thenReturn(Optional.of(
                reviewer(level)));
        return logins;
    }

    private static MockHttpServletRequest loggedIn(SessionSecurityContextRepository repository, AuthLevel level) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        repository.start(request, reviewer(level));
        return request;
    }

    @Test
    void aSessionLastsAtMostTheAbsoluteLimitHoweverActive() {
        LoginService logins = restoring(AuthLevel.FULL);
        MockHttpServletRequest request = loggedIn(at(LOGIN, logins), AuthLevel.FULL);

        assertThat(at(LOGIN.plus(Duration.ofHours(7)), logins).loadDeferredContext(request).get().getAuthentication())
                .isNotNull();
        assertThat(at(LOGIN.plus(Duration.ofHours(8).plusSeconds(1)), logins).loadDeferredContext(request).get()
                .getAuthentication()).isNull();
        assertThat(request.getSession(false)).as("invalidated").isNull();
    }

    @Test
    void rolesAndPermissionsCountOnlyOnceEveryFactorIsProven() {
        LoginService partial = restoring(AuthLevel.PASSWORD);
        MockHttpServletRequest request = loggedIn(at(LOGIN, partial), AuthLevel.PASSWORD);
        assertThat(at(LOGIN, partial).loadDeferredContext(request).get().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("AUTH_LEVEL_PASSWORD");

        LoginService full = restoring(AuthLevel.FULL);
        MockHttpServletRequest done = loggedIn(at(LOGIN, full), AuthLevel.FULL);
        assertThat(at(LOGIN, full).loadDeferredContext(done).get().getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder("AUTH_LEVEL_FULL", "ROLE_REVIEWER",
                        "parcel.read", "application.read", "review.recommend");
    }

    @Test
    void aDeletedOrDisabledAccountEndsTheSession() {
        LoginService gone = mock(LoginService.class);
        when(gone.restore(any(), any(), any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = loggedIn(at(LOGIN, gone), AuthLevel.FULL);

        assertThat(at(LOGIN, gone).loadDeferredContext(request).get().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void startingASessionReplacesAnyPreviousOne() {
        LoginService logins = restoring(AuthLevel.FULL);
        SessionSecurityContextRepository repository = at(LOGIN, logins);
        MockHttpServletRequest request = new MockHttpServletRequest();
        String planted = request.getSession(true).getId();

        repository.start(request, new AuthenticatedUser(USER, "Demo", AccountType.STAFF, Set.of(), Set.of(), java.util.List.of(), AuthLevel.FULL, LOGIN));

        assertThat(request.getSession(false).getId()).isNotEqualTo(planted);
    }
}
