// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.SecondFactorService;
import org.snigf.mvp.identity.application.WebAuthnCeremonies;
import org.snigf.mvp.identity.infrastructure.security.SecurityConfiguration;
import org.snigf.mvp.identity.infrastructure.security.SessionSecurityContextRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Second-factor endpoints (ADR-016, ADR-023), for a password session of a staff member:
 * register WebAuthn authenticators, prove one at login, or use a recovery code. Options and
 * responses are WebAuthn JSON, as {@code PublicKeyCredential.parseCreationOptionsFromJSON}
 * and {@code credential.toJSON()} produce in the browser.
 *
 * <p>The started ceremony's id is kept in the session (a plain string); its options stay on
 * the server. When the second factor is proven, the session is replaced by a new one at
 * level FULL and the CSRF token is renewed.
 */
@RestController
@RequestMapping(SecurityConfiguration.API + "/auth/mfa")
class MfaController {

    static final String CEREMONY = "snigf.mfa.ceremony";

    private final SecondFactorService secondFactor;
    private final SessionSecurityContextRepository sessions;
    private final CsrfTokenRepository csrfTokens;
    private final AuthRateLimiter rateLimiter;
    private final JsonMapper json = JsonMapper.builder().build();

    MfaController(SecondFactorService secondFactor, SessionSecurityContextRepository sessions,
                  CsrfTokenRepository csrfTokens, AuthRateLimiter rateLimiter) {
        this.secondFactor = secondFactor;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.rateLimiter = rateLimiter;
    }

    record RecoveryRequest(@NotNull @Size(max = 64) String code) {

        @Override
        public String toString() {
            return "RecoveryRequest[<redacted>]";
        }
    }

    @PostMapping(path = "/webauthn/registration/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    String registrationOptions(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        WebAuthnCeremonies.Started started = secondFactor.beginRegistration(user);
        remember(request, started);
        return started.optionsJson();
    }

    /** Body: {@code {"publicKey": {"credential": <PublicKeyCredential JSON>, "label": "..."}}}. */
    @PostMapping("/webauthn/registration")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Map<String, Object>> register(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @RequestBody String body, HttpServletRequest request,
                                                 HttpServletResponse response) {
        JsonNode publicKey = parse(body).path("publicKey");
        SecondFactorService.Enrolment enrolment = secondFactor.finishRegistration(user, ceremony(request),
                json.writeValueAsString(publicKey.path("credential")), publicKey.path("label").asString(""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", enrolment.registered());
        result.put("required", enrolment.required());
        result.put("enrolmentComplete", enrolment.complete());
        if (enrolment.upgraded() != null) {
            elevate(request, response, enrolment.upgraded());
            result.put("nextStep", "DONE");
            result.put("recoveryCodes", enrolment.recoveryCodes());   // shown once, never again
        } else {
            result.put("nextStep", enrolment.complete() ? "DONE" : "MFA_ENROLMENT");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(path = "/webauthn/assertion/options", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    String assertionOptions(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        WebAuthnCeremonies.Started started = secondFactor.beginAssertion(user);
        remember(request, started);
        return started.optionsJson();
    }

    /** Body: the {@code PublicKeyCredential} JSON of the assertion. */
    @PostMapping("/webauthn/assertion")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Map<String, Object>> assertion(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestBody String body, HttpServletRequest request,
                                                  HttpServletResponse response) {
        rateLimiter.check(AuthRateLimiter.Kind.LOGIN, request);
        SecondFactorService.Verification verification = secondFactor.finishAssertion(user, ceremony(request), body);
        return answer(verification, request, response, Map.of("nextStep", "DONE"));
    }

    @PostMapping("/recovery")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Map<String, Object>> recover(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody RecoveryRequest body, HttpServletRequest request,
                                                HttpServletResponse response) {
        rateLimiter.check(AuthRateLimiter.Kind.LOGIN, request);
        SecondFactorService.Verification verification = secondFactor.recover(user, body.code());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nextStep", "DONE");
        result.put("remainingRecoveryCodes", verification.remainingRecoveryCodes());
        // A recovery code means an authenticator is lost: the interface asks for a replacement.
        result.put("replaceAuthenticator", true);
        return answer(verification, request, response, result);
    }

    private ResponseEntity<Map<String, Object>> answer(SecondFactorService.Verification verification,
                                                       HttpServletRequest request, HttpServletResponse response,
                                                       Map<String, Object> success) {
        if (!verification.succeeded()) {
            if (verification.locked()) {
                sessions.end(request);   // a locked account keeps no half-authenticated session
                csrfTokens.saveToken(null, request, response);
            }
            throw new AuthExceptionHandler.SecondFactorFailed();
        }
        elevate(request, response, verification.upgraded());
        return ResponseEntity.ok(success);
    }

    private void elevate(HttpServletRequest request, HttpServletResponse response, AuthenticatedUser upgraded) {
        sessions.start(request, upgraded);
        csrfTokens.saveToken(null, request, response);
    }

    private static void remember(HttpServletRequest request, WebAuthnCeremonies.Started started) {
        request.getSession().setAttribute(CEREMONY, started.ceremonyId());
    }

    private static String ceremony(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object id = session.getAttribute(CEREMONY);
        session.removeAttribute(CEREMONY);   // single use
        return id instanceof String s ? s : null;
    }

    private JsonNode parse(String body) {
        if (body == null || body.length() > 40_000) {
            throw new WebAuthnCeremonies.Rejected("the request body is missing or too large");
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            throw new WebAuthnCeremonies.Rejected("the request body is not JSON", e);
        }
    }
}
