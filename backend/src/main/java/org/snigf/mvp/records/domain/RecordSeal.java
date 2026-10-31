// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.records.domain;

import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.records.CanonicalForm;

/**
 * What the {@code RECORD} key signs: the canonical form and all of the officer's signature. The
 * officer's evidence is bound too, so swapping it for another signature over the same record, or
 * replacing the copied public key, breaks the seal.
 */
public final class RecordSeal {

    private static final String VERSION = "snigf-record-seal-v1";

    private RecordSeal() {
    }

    public static byte[] message(byte[] canonical, OfficerSignature officer) {
        return CanonicalForm.of(VERSION)
                .bytes(canonical)
                .text(officer.credentialId())
                .bytes(officer.publicKeyCose())
                .bytes(officer.nonce())
                .bytes(officer.authenticatorData())
                .bytes(officer.clientDataJson())
                .bytes(officer.signature())
                .toBytes();
    }
}
