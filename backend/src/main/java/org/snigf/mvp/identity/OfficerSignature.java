// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity;

/**
 * An officer's WebAuthn signature over a record, with everything needed to verify it again
 * without the application (ADR-019). The public key is a copy: an officer may later remove a
 * lost key (ADR-028), and what they signed must stay verifiable.
 *
 * @param credentialId      the authenticator's credential id, base64url
 * @param publicKeyCose     the credential's public key (COSE, CBOR)
 * @param nonce             the single-use server nonce; the challenge was SHA-256(canonical ‖ nonce)
 * @param authenticatorData the authenticator data as signed
 * @param clientDataJson    the client data as signed
 * @param signature         the signature over authenticator data ‖ SHA-256(client data)
 */
public record OfficerSignature(String credentialId, byte[] publicKeyCose, byte[] nonce, byte[] authenticatorData,
                               byte[] clientDataJson, byte[] signature) {

    public OfficerSignature {
        publicKeyCose = publicKeyCose.clone();
        nonce = nonce.clone();
        authenticatorData = authenticatorData.clone();
        clientDataJson = clientDataJson.clone();
        signature = signature.clone();
    }

    @Override
    public byte[] publicKeyCose() {
        return publicKeyCose.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] authenticatorData() {
        return authenticatorData.clone();
    }

    @Override
    public byte[] clientDataJson() {
        return clientDataJson.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }
}
