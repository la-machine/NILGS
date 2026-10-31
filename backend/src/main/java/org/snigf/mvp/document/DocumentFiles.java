// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document;

import java.io.InputStream;

/**
 * The file half of ADR-033, for a module that keeps its own records of documents (the land register's
 * deposits): the same staging, inspection from the bytes, limits, random keys and hash checks as an
 * application's documents. It records nothing and decides nothing; the owning module records the
 * returned key with {@code document_storage_keys_known} knowing it, or the cleanup removes the file.
 */
public interface DocumentFiles {

    /** A file received and inspected, not yet kept. Closing forgets what was not kept. */
    interface Received extends AutoCloseable {
        String sha256();

        long sizeBytes();

        /** The cleaned display name. */
        String name();

        /** The media type read from the bytes. */
        String mediaType();

        /** Keeps the file under a new random key, which is returned. */
        String keep();

        @Override
        void close();
    }

    /** The file was refused, with the same codes as an application's documents; nothing was kept. */
    class Refused extends RuntimeException {
        private final String code;
        private final long sizeBytes;
        private final String detected;

        public Refused(String code, long sizeBytes, String detected) {
            super(code);
            this.code = code;
            this.sizeBytes = sizeBytes;
            this.detected = detected;
        }

        public String code() {
            return code;
        }

        /** The size received, or -1 when reading stopped at the limit. */
        public long sizeBytes() {
            return sizeBytes;
        }

        public String detected() {
            return detected;
        }
    }

    /** Stages at most the configured size, hashing as it arrives, and inspects the bytes. */
    Received receive(InputStream content, String givenName);

    /** Removes a kept file whose record could not be written. Never throws. */
    void forget(String storageKey);

    /** Whether the kept bytes still have this hash and size. */
    boolean intact(String storageKey, String sha256, long sizeBytes);

    InputStream open(String storageKey);
}
