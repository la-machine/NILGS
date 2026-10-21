// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.snigf.mvp.audit.Checkpoint;

/**
 * One checkpoint as evidence: all its copies merged, and whether they can be believed.
 *
 * @param checkpoint     the checkpoint, or null when its file is unreadable
 * @param chainPos       position it covers
 * @param inDatabase     a copy is in the database
 * @param inFile         a copy is in the archive
 * @param signatureValid every copy verifies and the copies are identical
 * @param readable       false for an archive file that could not be parsed
 */
public record CheckpointEvidence(Checkpoint checkpoint, long chainPos, boolean inDatabase, boolean inFile,
                                 boolean signatureValid, boolean readable) {

    public UUID id() {
        return checkpoint == null ? null : checkpoint.id();
    }

    /** Usable as an anchor: readable and validly signed. */
    public boolean trusted() {
        return readable && signatureValid;
    }

    /**
     * Merges the database and archive copies by id and checks every copy's signature.
     * A copy present in one place only is still evidence; copies that disagree are not.
     */
    public static List<CheckpointEvidence> merge(List<Checkpoint> database, List<Checkpoint> archive,
                                                 List<UnreadableCheckpoint> unreadable, Predicate<Checkpoint> signed) {
        Map<UUID, CheckpointEvidence> byId = new LinkedHashMap<>();
        for (Checkpoint c : database) {
            byId.put(c.id(), new CheckpointEvidence(c, c.chainPos(), true, false, signed.test(c), true));
        }
        for (Checkpoint c : archive) {
            CheckpointEvidence db = byId.get(c.id());
            boolean valid = signed.test(c) && (db == null || (db.signatureValid && db.checkpoint.equals(c)));
            byId.put(c.id(), new CheckpointEvidence(c, c.chainPos(), db != null, true, valid, true));
        }
        List<CheckpointEvidence> all = new ArrayList<>(byId.values());
        for (UnreadableCheckpoint u : unreadable) {
            all.add(new CheckpointEvidence(null, u.chainPos(), false, true, false, false));
        }
        all.sort(Comparator.comparingLong(CheckpointEvidence::chainPos));
        return all;
    }
}
