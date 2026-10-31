// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.application;

import java.util.UUID;

import org.snigf.mvp.identity.AccountType;
import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.identity.OfficerSignatures;
import org.snigf.mvp.identity.domain.UserAccount;
import org.snigf.mvp.identity.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Record-signing ceremonies (ADR-019, ADR-040 §5). Only an enabled staff account signs. A
 * finished signature joins the caller's transaction: the signature counter moves only if the
 * record it signs is written.
 */
@Service
public class OfficerSignatureService implements OfficerSignatures {

    private final WebAuthnCeremonies webauthn;
    private final UserRepository users;

    public OfficerSignatureService(WebAuthnCeremonies webauthn, UserRepository users) {
        this.webauthn = webauthn;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Started begin(UUID officerId, byte[] canonical) {
        officer(officerId);
        try {
            WebAuthnCeremonies.Started started = webauthn.beginSigning(officerId, canonical);
            return new Started(started.ceremonyId(), started.optionsJson());
        } catch (WebAuthnCeremonies.Rejected e) {
            throw new Refused(e.getMessage(), e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public OfficerSignature finish(String ceremonyId, UUID officerId, byte[] canonical, String responseJson) {
        officer(officerId);
        try {
            return webauthn.finishSigning(ceremonyId, officerId, canonical, responseJson);
        } catch (WebAuthnCeremonies.Rejected e) {
            throw new Refused(e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(byte[] canonical, OfficerSignature signature) {
        return webauthn.verifySignature(canonical, signature);
    }

    private void officer(UUID officerId) {
        UserAccount account = users.findById(officerId).filter(UserAccount::enabled)
                .filter(a -> a.accountType() == AccountType.STAFF)
                .orElseThrow(() -> new Refused("only an enabled staff account signs", null));
        if (!account.secondFactorReady()) {
            throw new Refused("the account has no security key", null);
        }
    }
}
