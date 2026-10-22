// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snigf.mvp.identity.domain.RecoveryCodeHasher;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.springframework.stereotype.Component;

/**
 * Recovery-code hashes: HMAC-SHA-256 with the OpenBao pepper key, over a domain-separated
 * message ({@value #DOMAIN} + code), so a code's MAC can never equal a password's. Stored
 * as {@code v<key version>:<hex>}; the version is kept so codes remain checkable if the
 * key is ever rotated (ADR-023).
 */
@Component
public class KeyedRecoveryCodeHasher implements RecoveryCodeHasher {

    static final String DOMAIN = "snigf-recovery-code-v1|";
    private static final Pattern STORED = Pattern.compile("v(\\d{1,9}):([0-9a-f]{64})");

    private final KeyService keys;

    public KeyedRecoveryCodeHasher(KeyService keys) {
        this.keys = keys;
    }

    @Override
    public String hash(String normalisedCode) {
        Mac mac = keys.mac(MacKey.PASSWORD_PEPPER, message(normalisedCode));
        return "v" + mac.keyVersion() + ":" + HexFormat.of().formatHex(mac.value());
    }

    @Override
    public boolean matches(String normalisedCode, String storedHash) {
        Matcher m = STORED.matcher(storedHash == null ? "" : storedHash);
        if (!m.matches()) {
            return false;
        }
        Mac mac = keys.mac(MacKey.PASSWORD_PEPPER, message(normalisedCode), Integer.parseInt(m.group(1)));
        return MessageDigest.isEqual(mac.value(), HexFormat.of().parseHex(m.group(2)));
    }

    private static byte[] message(String code) {
        return (DOMAIN + code).getBytes(StandardCharsets.UTF_8);
    }
}
