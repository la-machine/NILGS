// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.webauthn;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import org.snigf.mvp.identity.OfficerSignature;
import org.snigf.mvp.identity.domain.SigningChallenge;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Re-verifies a stored officer signature from its own contents, with no ceremony and no stored
 * credential (ADR-019): the assertion type, the challenge against the canonical form and nonce,
 * the relying party, user presence and verification, and the signature against the copied key.
 * Anything unreadable is simply not verified. The origin is not checked again: it was checked
 * when the signature was made, against the origins allowed at that time.
 */
final class SignatureEvidenceVerifier {

    private static final int RP_HASH_BYTES = 32;
    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_USER_VERIFIED = 0x04;
    private static final ObjectConverter COSE = new ObjectConverter();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final byte[] rpIdHash;

    SignatureEvidenceVerifier(String rpId) {
        this.rpIdHash = sha256(rpId.getBytes(StandardCharsets.UTF_8));
    }

    boolean verify(byte[] canonical, OfficerSignature evidence) {
        try {
            byte[] authenticatorData = evidence.authenticatorData();
            if (authenticatorData.length <= RP_HASH_BYTES
                    || !MessageDigest.isEqual(rpIdHash, Arrays.copyOf(authenticatorData, RP_HASH_BYTES))) {
                return false;
            }
            int flags = authenticatorData[RP_HASH_BYTES];
            if ((flags & FLAG_USER_PRESENT) == 0 || (flags & FLAG_USER_VERIFIED) == 0) {
                return false;
            }
            JsonNode client = JSON.readTree(evidence.clientDataJson());
            byte[] challenge = Base64.getUrlDecoder().decode(client.path("challenge").asString(""));
            if (!"webauthn.get".equals(client.path("type").asString(""))
                    || !MessageDigest.isEqual(challenge, SigningChallenge.of(canonical, evidence.nonce()))) {
                return false;
            }
            COSEKey key = COSE.getCborMapper().readValue(evidence.publicKeyCose(), COSEKey.class);
            Signature verifier = Signature.getInstance(key.getAlgorithm().toSignatureAlgorithm().getJcaName());
            verifier.initVerify(key.getPublicKey());
            verifier.update(authenticatorData);
            verifier.update(sha256(evidence.clientDataJson()));
            return verifier.verify(evidence.signature());
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
