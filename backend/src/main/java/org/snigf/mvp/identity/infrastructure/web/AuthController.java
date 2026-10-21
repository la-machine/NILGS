// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.application.LoginService;
import org.snigf.mvp.identity.application.RegistrationService;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.snigf.mvp.identity.infrastructure.security.SessionSecurityContextRepository;
import org.snigf.mvp.shared.security.PublicEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints (Slice 1 design §5): CSRF token, registration, login, session
 * view, logout. The second-factor endpoints arrive with WebAuthn (security step 4b).
 *
 * <p>Every failed login answers 401 {@code AUTH_FAILED}, whatever the reason; the reason is
 * in the audit trail only. Login and registration are throttled per client address.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/auth")
class AuthController {

    private final RegistrationService registrations;
    private final LoginService logins;
    private final SessionSecurityContextRepository sessions;
    private final CsrfTokenRepository csrfTokens;
    private final AuthRateLimiter rateLimiter;
    private final String csrfCookieName;

    AuthController(RegistrationService registrations, LoginService logins, SessionSecurityContextRepository sessions,
                   CsrfTokenRepository csrfTokens, AuthRateLimiter rateLimiter, IdentityProperties identity) {
        this.registrations = registrations;
        this.logins = logins;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.rateLimiter = rateLimiter;
        this.csrfCookieName = identity.csrf().cookieName();
    }

    /**
     * Where the token travels: the page reads the cookie and sends its value back in the
     * header (Spring's single-page-application mode). The value itself is not in the body:
     * the request-attribute token is BREACH-masked and only the cookie value is accepted
     * in the header.
     */
    record CsrfResponse(String cookieName, String headerName) {
    }

    record RegisterRequest(
            @NotNull @Size(max = 254) String email,
            @NotNull @Size(max = 120) String displayName,
            @NotNull @Size(max = 1024) String password,
            @NotNull @Pattern(regexp = "fr|en") String preferredLanguage) {

        @Override
        public String toString() {
            return "RegisterRequest[<redacted>]";
        }
    }

    record LoginRequest(@NotNull @Size(max = 254) String email, @NotNull @Size(max = 1024) String password) {

        @Override
        public String toString() {
            return "LoginRequest[<redacted>]";
        }
    }

    record LoginResponse(LoginService.NextStep nextStep) {
    }

    record SessionResponse(UUID userId, String displayName, String accountType, Set<String> roles, String authLevel,
                           boolean identityVerified, Instant authenticatedAt) {
    }

    /** Writes the token cookie (if absent); the page echoes it in the header on every change. */
    @GetMapping("/csrf")
    @PublicEndpoint("the browser needs a CSRF token before it can log in")
    CsrfResponse csrf(CsrfToken token) {
        token.getToken();   // resolves the deferred token, which writes the cookie
        return new CsrfResponse(csrfCookieName, token.getHeaderName());
    }

    /** 201 whether or not the address was new, so registration reveals no accounts. */
    @PostMapping("/register")
    @PublicEndpoint("self-registration of applicants")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        rateLimiter.check(AuthRateLimiter.Kind.REGISTER, request);
        registrations.register(new RegistrationService.Registration(body.email(), body.displayName(), body.password(),
                body.preferredLanguage()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    @PublicEndpoint("first factor of every login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                            HttpServletResponse response) {
        rateLimiter.check(AuthRateLimiter.Kind.LOGIN, request);
        LoginService.LoginResult result = logins.login(body.email(), body.password());
        if (!result.succeeded()) {
            throw new AuthExceptionHandler.AuthenticationFailed();
        }
        sessions.start(request, result.user());
        // A new session gets a new CSRF token: the page fetches it again.
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.ok(new LoginResponse(result.nextStep()));
    }

    @GetMapping("/session")
    @PreAuthorize("isAuthenticated()")
    SessionResponse session(@AuthenticationPrincipal AuthenticatedUser user) {
        return new SessionResponse(user.userId(), user.displayName(), user.accountType().name(), user.roles(),
                user.level().name(), false, user.authenticatedAt());
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request,
                                HttpServletResponse response) {
        logins.recordLogout(user);
        sessions.end(request);
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().build();
    }
}
