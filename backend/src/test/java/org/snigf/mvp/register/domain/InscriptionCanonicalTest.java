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
        return inscription(reason, holders, null);
    }

    private static Register.Inscription inscription(String reason, List<Register.Holder> holders, UUID application) {
        return new Register.Inscription(ID, ID, ID, "DEMO1", null, "CREATION", "PROPOSED", 0, "SYNTHETIC_DEMO", "DEMO-A", reason, List.of("DEMO-E"),
                List.of(), null, null, new BigDecimal("10000.00"), ID, ID, "Name", Instant.EPOCH, null, null, null, null, null, null, null,
                holders, null, null, null, null, application);
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

    @Test
    void aCreationFromACaseAlsoSignsTheCaseAndEveryOtherKeepsItsBytes() {
        UUID application = UUID.fromString("00000000-0000-0000-0000-00000000d001");
        byte[] synthetic = InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_A))), null);
        byte[] fromCase = InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_A)), application), null);
        assertThat(fromCase).as("the case is signed").isNotEqualTo(synthetic);
        assertThat(java.util.Arrays.copyOf(fromCase, synthetic.length)).as("appended: the earlier fields are unchanged").isEqualTo(synthetic);
        assertThat(new String(fromCase, StandardCharsets.UTF_8)).endsWith(application.toString());
    }

    @Test
    void aBoundInscriptionSignsItsPartiesIdentitiesItsDecisionAndTheUsufructsTerm() {
        UUID decision = UUID.fromString("00000000-0000-0000-0000-0000000000de");
        UUID statement = UUID.fromString("00000000-0000-0000-0000-0000000000ae");
        Register.Holder usufruct = new Register.Holder(PARTY_A, "DEMO Widow", "USUFRUCT", 1, 1, "LIFE", null,
                new Register.PartyIdentity("NATURAL_PERSON", "NATIONAL_ID", "DEMO-ID-1", statement, 3));
        Register.Holder bare = new Register.Holder(PARTY_B, "DEMO Child", "BARE_OWNERSHIP", 1, 1, null, null,
                new Register.PartyIdentity("NATURAL_PERSON", "NATIONAL_ID", "DEMO-ID-2", null, null));
        byte[] bound = InscriptionCanonical.of(bound(List.of(usufruct, bare), decision, statement), null);
        String text = new String(bound, StandardCharsets.UTF_8);
        assertThat(text).contains("decision", decision.toString(), "statement", statement.toString(), "usufruct", "LIFE", "parties", "DEMO-ID-1", "DEMO Widow");
        Register.Holder forged = new Register.Holder(PARTY_A, "DEMO Widow", "USUFRUCT", 1, 1, "LIFE", null,
                new Register.PartyIdentity("NATURAL_PERSON", "NATIONAL_ID", "DEMO-ID-FORGED", statement, 3));
        assertThat(InscriptionCanonical.of(bound(List.of(forged, bare), decision, statement), null)).as("a party's identity is signed").isNotEqualTo(bound);
        assertThat(InscriptionCanonical.of(inscription("A synthetic reason", List.of(holder(PARTY_A))), null))
                .as("an inscription from before V35 keeps its bytes").isEqualTo(InscriptionCanonical.of(inscription("A synthetic reason",
                        List.of(new Register.Holder(PARTY_A, "Display name", "CO_OWNERSHIP", 1, 2, null, null,
                                new Register.PartyIdentity("NATURAL_PERSON", "X", "Y", null, null)))), null));
    }

    private static Register.Inscription bound(List<Register.Holder> holders, UUID decision, UUID statement) {
        return new Register.Inscription(ID, ID, ID, "DEMO1", null, "CREATION", "PROPOSED", 0, "DECISION", "DEMO-APP", "A reason", List.of("DEMO-E"),
                List.of(), null, null, new BigDecimal("10000.00"), ID, ID, "Name", Instant.EPOCH, null, null, null, null, null, null, null,
                holders, null, null, null, null, ID, decision, statement, 2, true);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
