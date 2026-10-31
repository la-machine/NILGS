// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.domain;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.snigf.mvp.records.CanonicalForm;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical encoding's own rules. RegisterIT's verification holds it to the database's
 * {@code inscription_canonical}: a registered row re-encoded here must equal the bytes signed there.
 */
class InscriptionCanonicalTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PARTY_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PARTY_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Register.Inscription inscription(String reason, List<Register.Holder> holders) {
        return new Register.Inscription(ID, ID, ID, "DEMO1", null, "CREATION", "PROPOSED", 0, "SYNTHETIC_DEMO", "DEMO-A", reason, List.of("DEMO-E"),
                List.of(), null, null, new BigDecimal("10000.00"), ID, ID, "Name", Instant.EPOCH, null, null, null, null, null, null, null,
                holders, null, null, null, null);
    }

    private static Register.Holder holder(UUID party) {
        return new Register.Holder(party, "Display name", "CO_OWNERSHIP", 1, 2);
    }

    @Test
    void fieldsAreLengthPrefixedAndAnAbsentFieldDiffersFromAnEmptyOne() {
        assertThat(CanonicalForm.of("v").bytes(null).toBytes()).isNotEqualTo(CanonicalForm.of("v").bytes(new byte[0]).toBytes());
        assertThat(CanonicalForm.of("ab").text("c").toBytes()).isNotEqualTo(CanonicalForm.of("a").text("bc").toBytes());
        assertThat(CanonicalForm.of("é").toBytes()).isEqualTo(concat(ByteBuffer.allocate(4).putInt(2).array(), "é".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void itStartsWithItsVersionAndCoversTheContent() {
        byte[] encoded = InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_A))), null);
        assertThat(new String(encoded, StandardCharsets.UTF_8)).contains("snigf-inscription-v1").contains("A synthetic reason").contains("10000.00");
        assertThat(encoded).isNotEqualTo(InscriptionCanonical.of(inscription("Another reason", List.of(holder(PARTY_A))), null));
        assertThat(encoded).isNotEqualTo(InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_B))), null));
        assertThat(encoded).as("the previous hash is signed").isNotEqualTo(
                InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_A))), new byte[32]));
    }

    @Test
    void holdersAreEncodedInACanonicalOrderAndDisplayNamesAreNotSigned() {
        List<Register.Holder> one = new ArrayList<>(List.of(holder(PARTY_A), holder(PARTY_B)));
        List<Register.Holder> other = List.of(new Register.Holder(PARTY_B, "Renamed", "CO_OWNERSHIP", 1, 2), holder(PARTY_A));
        assertThat(InscriptionCanonical.of(inscription("A synthetic reason", one), null))
                .isEqualTo(InscriptionCanonical.of(inscription("A synthetic reason", other), null));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
