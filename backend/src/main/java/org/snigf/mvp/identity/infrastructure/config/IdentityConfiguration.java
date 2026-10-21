// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.domain.LockoutPolicy;
import org.snigf.mvp.identity.domain.PasswordPolicy;
import org.snigf.mvp.shared.config.DeploymentEnvironment;
import org.snigf.mvp.shared.config.SnigfProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Identity rules as beans, and a startup check of the settings that weaken authentication
 * when wrong. Outside tests it refuses to start unless:
 * <ul>
 *   <li>the session cookie is named with the {@code __Host-} prefix (no Domain, path
 *       {@code /}, HTTPS only, so no sibling host can set or read it) and is
 *       {@code SameSite} Strict or Lax; Secure and HttpOnly are fixed in code
 *       ({@link #sessionCookie});</li>
 *   <li>the CSRF cookie also has the {@code __Host-} prefix;</li>
 *   <li>Argon2id costs at least the OWASP minimum (19 MiB, 2 passes);</li>
 *   <li>the idle limit is shorter than the absolute limit.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

    static final String COMMON_PASSWORDS = "identity/common-passwords.txt";
    static final int OWASP_MIN_MEMORY_KIB = 19_456;
    static final int OWASP_MIN_ITERATIONS = 2;

    public IdentityConfiguration(IdentityProperties identity, SnigfProperties snigf, Environment environment) {
        List<String> violations = check(identity, snigf.environment(),
                environment.getProperty("spring.session.timeout", Duration.class));
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Unsafe identity configuration, refusing to start:\n - "
                    + String.join("\n - ", violations));
        }
    }

    static List<String> check(IdentityProperties identity, DeploymentEnvironment environment, Duration idleTimeout) {
        List<String> violations = new ArrayList<>();
        IdentityProperties.Session session = identity.session();
        if (identity.password().maxLength() < identity.password().minLength()) {
            violations.add("SNIGF_PASSWORD_MAX_LENGTH must not be below SNIGF_PASSWORD_MIN_LENGTH");
        }
        if (idleTimeout == null || idleTimeout.compareTo(session.absoluteTimeout()) > 0) {
            violations.add("SNIGF_SESSION_IDLE_TIMEOUT must be set and not exceed SNIGF_SESSION_ABSOLUTE_TIMEOUT");
        }
        if (!Set.of("strict", "lax").contains(session.cookieSameSite().toLowerCase(Locale.ROOT))) {
            violations.add("SNIGF_SESSION_COOKIE_SAME_SITE must be Strict or Lax");
        }
        if (environment == DeploymentEnvironment.TEST) {
            return violations;
        }
        if (!session.cookieName().startsWith("__Host-")) {
            violations.add("SNIGF_SESSION_COOKIE_NAME must start with __Host-");
        }
        if (!identity.csrf().cookieName().startsWith("__Host-")) {
            violations.add("SNIGF_CSRF_COOKIE_NAME must start with __Host-");
        }
        if (identity.password().minLength() < 12) {
            violations.add("SNIGF_PASSWORD_MIN_LENGTH must be at least 12 (P7)");
        }
        if (identity.password().argon2MemoryKib() < OWASP_MIN_MEMORY_KIB
                || identity.password().argon2Iterations() < OWASP_MIN_ITERATIONS) {
            violations.add("Argon2id must cost at least " + OWASP_MIN_MEMORY_KIB + " KiB and "
                    + OWASP_MIN_ITERATIONS + " passes (SNIGF_PASSWORD_ARGON2_*)");
        }
        return violations;
    }

    /**
     * The session cookie, defined here because Spring Boot 4 no longer applies
     * {@code server.servlet.session.cookie.*} to Spring Session (found by {@code AuthFlowIT}:
     * the cookie was a plain {@code SESSION}). Secure, HttpOnly and path {@code /} are not
     * settings, so they cannot be weakened.
     */
    @Bean
    CookieSerializer sessionCookie(IdentityProperties identity) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName(identity.session().cookieName());
        cookie.setUseSecureCookie(true);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setCookiePath("/");
        cookie.setSameSite(identity.session().cookieSameSite());
        return cookie;
    }

    @Bean
    PasswordPolicy passwordPolicy(IdentityProperties identity) {
        return new PasswordPolicy(identity.password().minLength(), identity.password().maxLength(), commonPasswords());
    }

    @Bean
    LockoutPolicy lockoutPolicy(IdentityProperties identity) {
        return new LockoutPolicy(identity.lockout().maxFailures(), identity.lockout().lockDuration());
    }

    /** The bundled list (P7); its provenance and checksum are in common-passwords.NOTICE.md. */
    static Set<String> commonPasswords() {
        Set<String> passwords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(COMMON_PASSWORDS).getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    passwords.add(line.strip().toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + COMMON_PASSWORDS, e);
        }
        if (passwords.isEmpty()) {
            throw new IllegalStateException(COMMON_PASSWORDS + " is empty");
        }
        return passwords;
    }
}
