// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.domain;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Recovery codes (ADR-023): 80 random bits, unambiguous alphabet, forgiving input. */
class RecoveryCodeTest {

    @Test
    void codesAreDistinctGroupsOfUnambiguousCharacters() {
        List<String> codes = RecoveryCode.generate(200);
        assertThat(codes).allMatch(c -> c.matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"));
        assertThat(new HashSet<>(codes)).hasSize(200);
    }

    @Test
    void typingIsForgiving() {
        assertThat(RecoveryCode.normalise("abcd-efgh-jkmn-pqrs")).contains("ABCDEFGHJKMNPQRS");
        assertThat(RecoveryCode.normalise(" abcd efgh jkmn pqrs ")).contains("ABCDEFGHJKMNPQRS");
        assertThat(RecoveryCode.normalise("OIL0-0000-0000-0000")).as("look-alikes").contains("0110000000000000");
    }

    @Test
    void anythingElseIsNoCode() {
        for (String bad : new String[] {null, "", "ABCD-EFGH-JKMN", "ABCD-EFGH-JKMN-PQRSX", "ABCD-EFGH-JKMN-PQR!",
                "U".repeat(16), "x".repeat(70)}) {
            assertThat(RecoveryCode.normalise(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
