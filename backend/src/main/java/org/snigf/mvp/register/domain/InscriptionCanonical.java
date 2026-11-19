// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.register.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.snigf.mvp.records.CanonicalForm;

/**
 * {@code snigf-inscription-v1} from an inscription's content (ADR-040 §5), the same bytes as the
 * database's {@code inscription_canonical} (a parity test holds the two together). Recomputing it
 * from the stored row detects a row changed behind the signatures.
 */
public final class InscriptionCanonical {

    public static final String VERSION = "snigf-inscription-v1";

    private InscriptionCanonical() {
    }

    public static byte[] of(Register.Inscription i, byte[] previousHash) {
        return of(i, previousHash, List.of());
    }

    /** @param documents the documents of the deposit a TRANSFER completes, by id; none adds nothing */
    public static byte[] of(Register.Inscription i, byte[] previousHash, List<Register.BoundDocument> documents) {
        CanonicalForm form = CanonicalForm.of(VERSION)
                .text(text(i.id()))
                .text(text(i.titleId()))
                .text(text(i.officeId()))
                .text(i.kind())
                .text(Integer.toString(i.expectedVersion()))
                .bytes(previousHash)
                .text(i.authorityType())
                .text(i.authorityReference())
                .text(i.reason())
                .text(Integer.toString(i.evidenceReferences().size()));
        i.evidenceReferences().forEach(form::text);
        form.text(Integer.toString(i.evidenceVersions().size()));
        i.evidenceVersions().forEach(v -> form.text(v.toString()));
        form.text(text(i.completesTransactionId()))
                .text(text(i.targetInscriptionId()))
                .text(decimal(i.areaM2()))
                .text(text(i.parcelVersionId()))
                .text(text(i.proposedBy()))
                .text(Integer.toString(i.holders().size()));
        List<Register.Holder> holders = i.holders().stream()
                .sorted(Comparator.comparing((Register.Holder h) -> h.partyId().toString()).thenComparing(Register.Holder::interest))
                .toList();
        for (Register.Holder h : holders) {
            form.text(h.partyId().toString()).text(h.interest()).text(Integer.toString(h.shareNum())).text(Integer.toString(h.shareDen()));
        }
        Register.Charge c = i.charge();
        form.text(c == null ? null : c.kind())
                .text(c == null ? null : text(c.beneficiaryId()))
                .text(c == null ? null : decimal(c.amount()))
                .text(c == null ? null : Boolean.toString(c.blocksTransfer()))
                .text(c == null || c.endsOn() == null ? null : c.endsOn().toString())
                .text(c == null ? null : c.note())
                .text("demo");
        if (!documents.isEmpty()) {
            form.text(Integer.toString(documents.size()));
            documents.forEach(d -> form.text(d.id().toString()).text(d.sha256()));
        }
        // V34: a CREATION from a case also names it; nothing is added otherwise, so earlier bytes are unchanged.
        if (i.applicationId() != null) {
            form.text("case").text(i.applicationId().toString());
        }
        // V35 (ADR-043, owner's review): the decision and statement it was proposed under; the usufruct's term; and, for
        // every inscription proposed from V35 on, its parties' identities, so a party changed behind the signatures shows.
        if (i.decisionId() != null) {
            form.text("decision").text(i.decisionId().toString()).text("statement").text(text(i.ownershipStatementId()))
                    .text(i.ownershipStatementVersion() == null ? null : i.ownershipStatementVersion().toString());
        }
        List<Register.Holder> withTerm = holders.stream().filter(h -> h.usufructTerm() != null).toList();
        if (!withTerm.isEmpty()) {
            form.text("usufruct").text(Integer.toString(withTerm.size()));
            for (Register.Holder h : withTerm) {
                form.text(h.partyId().toString()).text(h.interest()).text(h.usufructTerm())
                        .text(h.usufructUntil() == null ? null : h.usufructUntil().toString());
            }
        }
        if (i.bindsParties()) {
            form.text("parties").text(Integer.toString(holders.size()));
            for (Register.Holder h : holders) {
                Register.PartyIdentity p = h.identity();
                form.text(h.partyId().toString()).text(h.interest()).text(p == null ? null : p.partyKind()).text(h.partyName())
                        .text(p == null ? null : p.identityKind()).text(p == null ? null : p.identityNumber())
                        .text(p == null ? null : text(p.sourceIdentityId()))
                        .text(p == null || p.sourceIdentityVersion() == null ? null : p.sourceIdentityVersion().toString());
            }
            Register.PartyIdentity b = c == null ? null : c.beneficiaryIdentity();
            form.text("beneficiary").text(c == null ? null : text(c.beneficiaryId())).text(b == null ? null : b.partyKind())
                    .text(c == null ? null : c.beneficiaryName()).text(b == null ? null : b.identityKind()).text(b == null ? null : b.identityNumber());
        }
        return form.toBytes();
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
