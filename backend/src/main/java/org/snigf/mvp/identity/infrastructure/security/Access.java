// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.snigf.mvp.identity.ActiveGrant;
import org.snigf.mvp.identity.AuthenticatedUser;
import org.snigf.mvp.identity.application.SpecialGrantService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one permission check every endpoint uses (ADR-025):
 * <pre>
 *   &#64;PreAuthorize("@access.can(authentication, 'audit.event.search')")
 *   &#64;PreAuthorize("@access.canAt(authentication, 'parcel.create', #body.officeCode())")
 * </pre>
 * Allowed when a role grants the permission everywhere ({@code audit.event.search}) or at that
 * office ({@code parcel.create@DEMO1}); otherwise when a special grant in force covers it. A
 * special grant is then used: one use is taken before the action
 * ({@link SpecialGrantService#reserve}), and {@link SpecialGrantUseInterceptor} confirms it
 * (audited) or gives it back when the action fails. Nothing counts before every factor is
 * proven: the session at level PASSWORD holds no permission and no special grant.
 */
@Component("access")
public class Access {

    static final String RESERVED = Access.class.getName() + ".reserved";

    /** A use taken for this request. */
    record Reserved(ActiveGrant grant) {
    }

    private final SpecialGrantService specialGrants;

    public Access(SpecialGrantService specialGrants) {
        this.specialGrants = specialGrants;
    }

    /** The permission held everywhere. */
    public boolean can(Authentication authentication, String permission) {
        return check(authentication, permission, null);
    }

    /**
     * The permission held everywhere or at {@code officeCode}. Without an office (for example an
     * unknown record) only "everywhere" can match, so such a holder then gets 404 rather than 403.
     */
    public boolean canAt(Authentication authentication, String permission, String officeCode) {
        return check(authentication, permission, officeCode);
    }

    /**
     * The permission held everywhere or at one of {@code officeCodes}, for a record that belongs
     * to more than one office (an alert between two parcels: {@code @alertOffices.of(#id)}).
     * At most one special grant use is taken: the first office that matches. Without an office
     * only "everywhere" can match, as for {@link #canAt}.
     */
    public boolean canAtAny(Authentication authentication, String permission, Collection<String> officeCodes) {
        if (officeCodes == null || officeCodes.isEmpty()) {
            return check(authentication, permission, null);
        }
        for (String officeCode : officeCodes) {
            if (check(authentication, permission, officeCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The permission held for the records assigned to the person ({@code review.recommend#assigned}),
     * for endpoints whose service then works only on the cases assigned to the caller or open to
     * them at their stage (row-level security decides which, V16). Role permissions only.
     */
    public boolean canAssigned(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user) || !user.fullyAuthenticated()) {
            return false;
        }
        String assigned = permission + "#assigned";
        return authentication.getAuthorities().stream().anyMatch(a -> assigned.equals(a.getAuthority()));
    }

    /**
     * The permission held for the person's own records ({@code application.create#own}), for
     * endpoints whose service then works only on the caller's records (and answers 404 for any
     * other; row-level security agrees, V13). Role permissions only: such permissions are never
     * delegable to another person's records.
     */
    public boolean canOwn(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user) || !user.fullyAuthenticated()) {
            return false;
        }
        String own = permission + "#own";
        return authentication.getAuthorities().stream().anyMatch(a -> own.equals(a.getAuthority()));
    }

    /**
     * The permission held everywhere or at some office, for endpoints whose service then
     * restricts each record to the offices held (the supervisor's reset confirmations,
     * ADR-028). Role permissions only: such permissions are never delegable.
     */
    public boolean canSomewhere(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user) || !user.fullyAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String granted = authority.getAuthority();
            if (permission.equals(granted) || granted.startsWith(permission + "@")) {
                return true;
            }
        }
        return false;
    }

    private boolean check(Authentication authentication, String permission, String officeCode) {
        if (authentication == null || !authentication.isAuthenticated() || permission == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user) || !user.fullyAuthenticated()) {
            return false;
        }
        String scoped = officeCode == null ? null : permission + "@" + officeCode;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String granted = authority.getAuthority();
            if (permission.equals(granted) || (scoped != null && scoped.equals(granted))) {
                return true;
            }
        }
        for (ActiveGrant grant : user.specialGrants()) {
            boolean covers = grant.permission().equals(permission)
                    && (grant.officeCode() == null || grant.officeCode().equals(officeCode));
            if (covers && specialGrants.reserve(grant.id())) {
                remember(grant);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void remember(ActiveGrant grant) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            List<Reserved> reserved = (List<Reserved>) request.getAttribute(RESERVED);
            if (reserved == null) {
                reserved = new ArrayList<>();
                request.setAttribute(RESERVED, reserved);
            }
            reserved.add(new Reserved(grant));
        }
    }
}
