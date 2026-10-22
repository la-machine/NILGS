// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletResponse;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * The single security filter chain (ADR-004, ADR-016, Slice 1 design §6).
 *
 * <ul>
 *   <li><b>Default deny.</b> Health and info, the CSRF token, login and registration are
 *       public; the session view, logout and second-factor endpoints need any authenticated
 *       session (the service checks the level); everything else
 *       under {@code /api/v1} needs a fully authenticated session ({@link AuthLevel#FULL})
 *       and, per method, its permission ({@code @PreAuthorize}); anything else is denied.</li>
 *   <li><b>Sessions</b> in PostgreSQL (Spring Session), holding only plain values
 *       ({@link SessionSecurityContextRepository}); no request cache, no form login, no HTTP
 *       Basic, no remember-me.</li>
 *   <li><b>CSRF</b> on every state-changing call, including login (login CSRF): Spring's
 *       single-page-application mode, the token in a {@code __Host-} cookie the browser
 *       script reads and echoes in a header.</li>
 *   <li><b>Errors</b>: 401 {@code AUTH_REQUIRED} without a session, 403 {@code ACCESS_DENIED}
 *       or {@code CSRF_INVALID} otherwise; refusals of authenticated users are audited.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    public static final String API = "/api/v1";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionSecurityContextRepository contexts,
                                            AuditingAccessDeniedHandler denied, CookieCsrfTokenRepository csrfTokens)
            throws Exception {
        http
            .securityContext(context -> context.securityContextRepository(contexts))
            .requestCache(cache -> cache.disable())
            .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, API + "/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, API + "/auth/login", API + "/auth/register").permitAll()
                .requestMatchers(API + "/auth/session", API + "/auth/logout", API + "/auth/mfa/**").authenticated()
                .requestMatchers(API + "/**").hasAuthority(AuthLevel.FULL.authority())
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, e) -> ProblemResponses.write(response,
                        HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized", "AUTH_REQUIRED"))
                .accessDeniedHandler(denied))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .rememberMe(remember -> remember.disable());
        return http.build();
    }

    /** {@code __Host-} cookie: Secure, path /, no Domain; readable by the page script (SPA mode). */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(IdentityProperties identity) {
        IdentityProperties.Csrf settings = identity.csrf();
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(settings.cookieName());
        repository.setHeaderName(settings.headerName());
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        return repository;
    }

    /**
     * Accounts are in the database, reached only through the login endpoint. An empty store
     * stops Spring Boot from creating a default user and printing its password.
     */
    @Bean
    UserDetailsService noUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
