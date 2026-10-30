// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys.internal;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.snigf.mvp.keys.KeyServiceException;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningPublicKey;
import org.snigf.mvp.shared.config.SnigfProperties;
import tools.jackson.databind.JsonNode;

/**
 * Keys held by the OpenBao transit engine (ADR-018). Signing, data-key generation and
 * unwrapping, and HMAC happen inside OpenBao; the application receives signatures, MACs
 * and one-time data keys, never a long-term key. Only the paths granted by
 * infra/openbao/app-policy.hcl.template are called.
 */
class OpenBaoKeyService extends CustodyKeyService {

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final OpenBaoClient client;
    private final String transit;
    private final SnigfProperties.KeyNames names;

    OpenBaoKeyService(OpenBaoClient client, String transitMount, SnigfProperties.KeyNames names) {
        super(names);
        this.client = client;
        this.transit = transitMount;
        this.names = names;
    }

    @Override
    Signature signWith(String keyName, byte[] message) {
        JsonNode data = data(client.post(transit + "/sign/" + keyName, Map.of("input", B64.encodeToString(message))));
        Versioned v = Versioned.parse(text(data, "signature"));
        return new Signature(v.version(), v.value());
    }

    @Override
    List<SigningPublicKey> fetchPublicKeys(String keyName) {
        JsonNode data = data(client.get(transit + "/keys/" + keyName));
        if (!"ed25519".equals(text(data, "type"))) {
            throw new KeyServiceException("key " + keyName + " is not an Ed25519 key");
        }
        List<SigningPublicKey> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> version : data.path("keys").properties()) {
            keys.add(new SigningPublicKey(Integer.parseInt(version.getKey()),
                    B64D.decode(text(version.getValue(), "public_key"))));
        }
        if (keys.isEmpty()) {
            throw new KeyServiceException("key " + keyName + " has no public key");
        }
        keys.sort(Comparator.comparingInt(SigningPublicKey::version));
        return List.copyOf(keys);
    }

    @Override
    DataKey newDataKey(String keyName, byte[] context) {
        JsonNode data = data(client.post(transit + "/datakey/plaintext/" + keyName,
                Map.of("context", B64.encodeToString(context), "bits", DATA_KEY_BYTES * 8)));
        return new DataKey(B64D.decode(text(data, "plaintext")), text(data, "ciphertext"));
    }

    @Override
    byte[] unwrapDataKey(String keyName, String wrapped, byte[] context) {
        JsonNode data = data(client.post(transit + "/decrypt/" + keyName,
                Map.of("ciphertext", wrapped, "context", B64.encodeToString(context))));
        return B64D.decode(text(data, "plaintext"));
    }

    @Override
    Mac macWith(String keyName, byte[] message, Integer version) {
        Map<String, Object> body = new HashMap<>();
        body.put("input", B64.encodeToString(message));
        if (version != null) {
            body.put("key_version", version);
        }
        JsonNode data = data(client.post(transit + "/hmac/" + keyName + "/sha2-256", body));
        Versioned v = Versioned.parse(text(data, "hmac"));
        return new Mac(v.version(), v.value());
    }

    @Override
    void verifyCustody() {
        expect(names.auditCheckpoint(), "ed25519", false);
        expect(names.recordSigning(), "ed25519", false);
        expect(names.documentIssuance(), "ed25519", false);
        expect(names.dataEncryption(), "aes256-gcm96", true);
        expect(names.paymentCallback(), "hmac", false);
        expect(names.passwordPepper(), "hmac", false);
        expect(names.blindIndex(), "hmac", false);
    }

    private void expect(String keyName, String type, boolean derived) {
        JsonNode data = data(client.get(transit + "/keys/" + keyName));
        List<String> problems = new ArrayList<>();
        if (!type.equals(text(data, "type"))) {
            problems.add("type is " + text(data, "type") + ", expected " + type);
        }
        if (data.path("exportable").asBoolean(true)) {
            problems.add("exportable");
        }
        if (data.path("allow_plaintext_backup").asBoolean(true)) {
            problems.add("plaintext backup allowed");
        }
        if (data.path("deletion_allowed").asBoolean(true)) {
            problems.add("deletion allowed");
        }
        if (data.path("derived").asBoolean(false) != derived) {
            problems.add(derived ? "not derived per context" : "unexpectedly derived");
        }
        if (!problems.isEmpty()) {
            throw new KeyServiceException("key " + keyName + " fails custody rules: " + String.join(", ", problems));
        }
    }

    @Override
    void checkAvailable() {
        data(client.get("auth/token/lookup-self"));
    }

    private static JsonNode data(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new KeyServiceException("OpenBao returned no data");
        }
        return data;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString("");
        if (value.isEmpty()) {
            throw new KeyServiceException("OpenBao response lacks '" + field + "'");
        }
        return value;
    }

    /** Transit's {@code <prefix>:v<version>:<base64>} values; the prefix is not interpreted. */
    record Versioned(int version, byte[] value) {

        static Versioned parse(String encoded) {
            String[] parts = encoded.split(":");
            if (parts.length != 3 || !parts[1].startsWith("v")) {
                throw new KeyServiceException("unexpected transit value format");
            }
            try {
                return new Versioned(Integer.parseInt(parts[1].substring(1)), B64D.decode(parts[2]));
            } catch (IllegalArgumentException e) {
                throw new KeyServiceException("unexpected transit value format");
            }
        }
    }
}
