// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.records.application;

import java.util.UUID;

import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.identity.OfficerSignatures;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningKey;
import org.snigf.mvp.records.SignedRecord;
import org.snigf.mvp.records.SignedRecords;
import org.snigf.mvp.records.domain.RecordSeal;
import org.springframework.stereotype.Service;

/** The officer's signature, then the system's seal (ADR-019, ADR-040 §5). */
@Service
public class SignedRecordService implements SignedRecords {

    private final OfficerSignatures officers;
    private final KeyService keys;

    public SignedRecordService(OfficerSignatures officers, KeyService keys) {
        this.officers = officers;
        this.keys = keys;
    }

    @Override
    public OfficerSignatures.Started begin(UUID officerId, byte[] canonical) {
        return officers.begin(officerId, canonical);
    }

    @Override
    public SignedRecord seal(String ceremonyId, UUID officerId, byte[] canonical, String responseJson) {
        OfficerSignature officer = officers.finish(ceremonyId, officerId, canonical, responseJson);
        Signature server = keys.sign(SigningKey.RECORD, RecordSeal.message(canonical, officer));
        return new SignedRecord(canonical, officer, server);
    }

    @Override
    public boolean verify(SignedRecord record) {
        return officers.verify(record.canonical(), record.officer())
                && keys.verify(SigningKey.RECORD, RecordSeal.message(record.canonical(), record.officer()), record.server());
    }
}
