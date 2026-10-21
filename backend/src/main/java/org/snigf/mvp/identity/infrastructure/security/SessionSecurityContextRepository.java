// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snigf.mvp.identity.AuthLevel;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.application.LoginService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Keeps authentication in the server-side session as three plain values (user id, level,
 * login time) and rebuilds the principal from the database on every request
 * ({@link LoginService#restore}). A disabled account or a revoked role therefore takes
 * effect on the next request, and the session never stores an object graph
 * ({@code SessionSerializationConfiguration}).
 *
 * <p>Enforces the absolute session limit (P5): a session older than
 * {@code SNIGF_SESSION_ABSOLUTE_TIMEOUT} since login is invalidated, however active.
 * Spring Session enforces the idle limit.
 */
@Component
public class SessionSecurityContextRepository implements SecurityContextRepository {

    static final String USER = "snigf.auth.user";
    static final String LEVEL = "snigf.auth.level";
    static final String AUTHENTICATED_AT = "snigf.auth.at";
    private static final Logger log = LoggerFactory.getLogger(SessionSecurityContextRepository.class);

    private final LoginService logins;
    private final Duration absoluteTimeout;
    private final Clock clock;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public SessionSecurityContextRepository(LoginService logins, IdentityProperties properties, Clock clock) {
        this.logins = logins;
        this.absoluteTimeout = properties.session().absoluteTimeout();
        this.clock = clock;
    }

    /** Starts an authenticated session: a new session id (no fixation), then the three values. */
    public void start(HttpServletRequest request, AuthenticatedUser user) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(USER, user.userId().toString());
        session.setAttribute(LEVEL, user.level().name());
        session.setAttribute(AUTHENTICATED_AT, user.authenticatedAt().toEpochMilli());
        // Indexed by Spring Session, so every session of a user can be revoked at once.
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, user.userId().toString());
    }

    /** Ends the session on the server; the cookie becomes useless. */
    public void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }

    /** Loaded on first use and once per request: one database read for an authenticated call. */
    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new DeferredSecurityContext() {
            private SecurityContext context;
            private boolean generated;

            @Override
            public SecurityContext get() {
                if (context == null) {
                    context = holder.createEmptyContext();
                    Optional<AuthenticatedUser> user = restore(request);
                    user.ifPresent(u -> context.setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(u, null, authorities(u))));
                    generated = user.isEmpty();
                }
                return context;
            }

            @Override
            public boolean isGenerated() {
                get();
                return generated;
            }
        };
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(org.springframework.security.web.context.HttpRequestResponseHolder holder) {
        return loadDeferredContext(holder.getRequest()).get();
    }

    /** Sessions are written explicitly by {@link #start}; nothing is saved implicitly. */
    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(USER) != null;
    }

    private Optional<AuthenticatedUser> restore(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object userValue;
        Object levelValue;
        Object atValue;
        try {
            userValue = session.getAttribute(USER);
            levelValue = session.getAttribute(LEVEL);
            atValue = session.getAttribute(AUTHENTICATED_AT);
        } catch (RuntimeException refused) {
            // A stored value that is not a plain value (SessionSerializationConfiguration):
            // someone wrote to the session table. End the session, never run its content.
            log.warn("Session ended: a stored attribute was refused by the deserialization filter");
            invalidateQuietly(session);
            return Optional.empty();
        }
        if (!(userValue instanceof String userId) || !(levelValue instanceof String level)
                || !(atValue instanceof Long at)) {
            return Optional.empty();
        }
        Instant authenticatedAt = Instant.ofEpochMilli(at);
        if (authenticatedAt.plus(absoluteTimeout).isBefore(clock.instant())) {
            session.invalidate();
            return Optional.empty();
        }
        Optional<AuthenticatedUser> user;
        try {
            user = logins.restore(UUID.fromString(userId), AuthLevel.valueOf(level), authenticatedAt);
        } catch (IllegalArgumentException e) {
            user = Optional.empty();
        }
        if (user.isEmpty()) {
            log.info("Session ended: its account no longer exists or is disabled");
            session.invalidate();
        }
        return user;
    }

    private static void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalid) {
            // nothing left to end
        }
    }

    static List<GrantedAuthority> authorities(AuthenticatedUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(user.level().authority()));
        if (user.fullyAuthenticated()) {
            // Roles count only once every factor is proven.
            user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return authorities;
    }
}
