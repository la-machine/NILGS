// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.util.ArrayList;
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

    /** The permission held everywhere or at {@code officeCode}. */
    public boolean canAt(Authentication authentication, String permission, String officeCode) {
        return officeCode != null && check(authentication, permission, officeCode);
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
