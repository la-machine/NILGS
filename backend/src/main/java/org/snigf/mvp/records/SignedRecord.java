// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.records;

import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.keys.Signature;

/**
 * A record both signed by an officer and sealed by the system (ADR-019): it proves "this officer
 * approved it" and "this system recorded it".
 *
 * @param canonical the record's canonical bytes (see {@link CanonicalForm})
 * @param officer   the officer's WebAuthn signature over them
 * @param server    the {@code RECORD} key's signature over them and the officer's signature
 */
public record SignedRecord(byte[] canonical, OfficerSignature officer, Signature server) {

    public SignedRecord {
        canonical = canonical.clone();
    }

    @Override
    public byte[] canonical() {
        return canonical.clone();
    }
}
