// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snigf.mvp.identity.application.IdentityProperties;
import org.snigf.mvp.identity.domain.PasswordHasher;
import org.snigf.mvp.keys.KeyService;
import org.snigf.mvp.keys.Mac;
import org.snigf.mvp.keys.MacKey;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing in two stages:
 * <ol>
 *   <li><b>Pepper</b>: HMAC-SHA-256 of the password with the OpenBao key
 *       {@link MacKey#PASSWORD_PEPPER}, which never leaves OpenBao;</li>
 *   <li><b>Argon2id</b> of that MAC with a random salt and the cost from {@code .env}.</li>
 * </ol>
 * Stored form: {@code $snigf-pepper$v=<pepper version>$<Argon2 encoded hash>}. A database
 * dump alone allows no offline guessing (the pepper is missing), and OpenBao alone holds no
 * hash. The pepper version is recorded so the hash stays verifiable if the pepper key is
 * ever rotated, and is then upgraded at the next login.
 *
 * <p>At most {@code maxConcurrentHashes} hashes run at once: each costs
 * {@code argon2MemoryKib} of memory, and an unbounded number would let a flood of logins
 * exhaust the heap. Further callers wait up to {@code hashQueueTimeout}, then get
 * {@link PasswordHasher.Busy}.
 */
@Component
public class PepperedArgon2PasswordHasher implements PasswordHasher {

    static final String PREFIX = "$snigf-pepper$v=";
    private static final Pattern STORED = Pattern.compile("\\$snigf-pepper\\$v=(\\d{1,9})\\$(\\$argon2id\\$.+)");
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final KeyService keys;
    private final Argon2PasswordEncoder argon2;
    private final Semaphore capacity;
    private final long queueTimeoutMillis;
    private final AtomicInteger currentPepperVersion = new AtomicInteger();
    private volatile String decoy;

    public PepperedArgon2PasswordHasher(KeyService keys, IdentityProperties properties) {
        IdentityProperties.Password p = properties.password();
        this.keys = keys;
        this.argon2 = new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, p.argon2Parallelism(), p.argon2MemoryKib(),
                p.argon2Iterations());
        this.capacity = new Semaphore(p.maxConcurrentHashes(), true);
        this.queueTimeoutMillis = p.hashQueueTimeout().toMillis();
    }

    @Override
    public String hash(String password) {
        return bounded(() -> {
            Mac pepper = keys.mac(MacKey.PASSWORD_PEPPER, bytes(password));
            currentPepperVersion.accumulateAndGet(pepper.keyVersion(), Math::max);
            return PREFIX + pepper.keyVersion() + "$" + argon2.encode(peppered(pepper));
        });
    }

    @Override
    public boolean matches(String password, String storedHash) {
        Matcher m = STORED.matcher(storedHash == null ? "" : storedHash);
        if (!m.matches()) {
            spendEquivalentTime(password);   // malformed or legacy hash: never matches
            return false;
        }
        int version = Integer.parseInt(m.group(1));
        return bounded(() -> argon2.matches(peppered(keys.mac(MacKey.PASSWORD_PEPPER, bytes(password), version)),
                m.group(2)));
    }

    @Override
    public boolean needsRehash(String storedHash) {
        Matcher m = STORED.matcher(storedHash == null ? "" : storedHash);
        if (!m.matches()) {
            return true;
        }
        int version = Integer.parseInt(m.group(1));
        return version < currentPepperVersion.get() || argon2.upgradeEncoding(m.group(2));
    }

    @Override
    public void spendEquivalentTime(String password) {
        String target = decoy;
        if (target == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            target = hash(HexFormat.of().formatHex(random));
            decoy = target;
        }
        matches(password == null ? "" : password, target);
    }

    private static String peppered(Mac pepper) {
        return Base64.getEncoder().encodeToString(pepper.value());
    }

    private static byte[] bytes(String password) {
        return (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
    }

    private <T> T bounded(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(queueTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy();
        }
        if (!acquired) {
            throw new Busy();
        }
        try {
            return work.get();
        } finally {
            capacity.release();
        }
    }
}
