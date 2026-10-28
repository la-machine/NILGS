// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Where document bytes live (Baseline: a filesystem adapter for the MVP, an S3-API adapter for
 * the pilot, P-02). Files are kept under random keys, never under their names. An upload is
 * first staged (counted, hashed, inspected), then committed or discarded.
 */
public interface DocumentStorage {

    /** A file received but not yet kept. */
    record Staged(Path file, long size, String sha256) {
    }

    /** A kept object and when it was last written, for the cleanup. */
    record StoredObject(String key, Instant modified) {
    }

    /** The upload was longer than allowed; nothing was kept. */
    class TooLarge extends IOException {
        public TooLarge() {
            super("upload larger than allowed");
        }
    }

    /** Copies at most {@code maxBytes} into staging, hashing as it goes. */
    Staged stage(InputStream content, long maxBytes) throws IOException;

    /** Keeps a staged file under a new random key; returns the key. */
    String commit(Staged staged) throws IOException;

    /** Forgets a staged file. Never throws. */
    void discard(Staged staged);

    InputStream open(String key) throws IOException;

    /** Deletes a kept object if it exists. */
    void delete(String key) throws IOException;

    /** Kept objects last written before {@code before}. */
    List<StoredObject> objectsOlderThan(Instant before) throws IOException;

    /** Removes staged leftovers older than {@code before}; returns how many. */
    int purgeStaging(Instant before) throws IOException;
}
