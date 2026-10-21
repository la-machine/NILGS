// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered identifiers (RFC 9562 version 7, ADR-008): 48 bits of Unix milliseconds,
 * then 74 random bits from a {@link SecureRandom}. Ordered by creation time, so index
 * inserts stay local, while the random part keeps them unguessable.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next() {
        return at(System.currentTimeMillis());
    }

    static UUID at(long epochMillis) {
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong();
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
        long lsb = randB & 0x3FFF_FFFF_FFFF_FFFFL | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
