// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.session;

import java.util.UUID;

import org.snigf.mvp.identity.application.SessionRevoker;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Ends every session of an account through Spring Session's principal index: each session
 * stores the account id under {@code PRINCIPAL_NAME_INDEX_NAME} at login
 * ({@code SessionSecurityContextRepository.start}), so they can all be found and deleted.
 */
@Component
public class SpringSessionRevoker implements SessionRevoker {

    private final FindByIndexNameSessionRepository<?> sessions;

    public SpringSessionRevoker(FindByIndexNameSessionRepository<?> sessions) {
        this.sessions = sessions;
    }

    @Override
    public int revokeAll(UUID userId) {
        var found = sessions.findByPrincipalName(userId.toString()).keySet();
        found.forEach(sessions::deleteById);
        return found.size();
    }
}
