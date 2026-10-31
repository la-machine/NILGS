// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.snigf.mvp.document.DocumentFiles;
import org.snigf.mvp.document.domain.DocumentName;
import org.snigf.mvp.document.domain.Inspection;
import org.springframework.stereotype.Service;

/** {@link DocumentFiles} on the storage, inspector and limits of {@link DocumentService} (ADR-033). */
@Service
public class DocumentFileService implements DocumentFiles {

    private final DocumentStorage storage;
    private final ContentInspector inspector;
    private final DocumentProperties settings;

    public DocumentFileService(DocumentStorage storage, ContentInspector inspector, DocumentProperties settings) {
        this.storage = storage;
        this.inspector = inspector;
        this.settings = settings;
    }

    @Override
    public Received receive(InputStream content, String givenName) {
        String name = DocumentName.clean(givenName);
        if (name.isEmpty()) {
            throw new Refused("NAME_REQUIRED", 0, null);
        }
        DocumentStorage.Staged staged;
        try {
            staged = storage.stage(content, settings.maxBytes());
        } catch (DocumentStorage.TooLarge e) {
            throw new Refused("FILE_TOO_LARGE", -1, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean accepted = false;
        try {
            if (staged.size() == 0) {
                throw new Refused("FILE_EMPTY", 0, null);
            }
            Inspection inspection = inspector.inspect(staged.file(), name);
            if (!inspection.ok()) {
                throw new Refused(inspection.code(), staged.size(), inspection.detected());
            }
            accepted = true;
            return new StagedFile(staged, name, inspection.type().mediaType());
        } finally {
            if (!accepted) {
                storage.discard(staged);
            }
        }
    }

    @Override
    public void forget(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException ignored) {
            // The cleanup removes it once the grace period is over.
        }
    }

    @Override
    public boolean intact(String storageKey, String sha256, long sizeBytes) {
        try (DigestInputStream in = new DigestInputStream(storage.open(storageKey), MessageDigest.getInstance("SHA-256"))) {
            long size = in.transferTo(OutputStream.nullOutputStream());
            return size == sizeBytes && HexFormat.of().formatHex(in.getMessageDigest().digest()).equals(sha256);
        } catch (IOException e) {
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            return storage.open(storageKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final class StagedFile implements Received {
        private final DocumentStorage.Staged staged;
        private final String name;
        private final String mediaType;

        StagedFile(DocumentStorage.Staged staged, String name, String mediaType) {
            this.staged = staged;
            this.name = name;
            this.mediaType = mediaType;
        }

        @Override
        public String sha256() {
            return staged.sha256();
        }

        @Override
        public long sizeBytes() {
            return staged.size();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String mediaType() {
            return mediaType;
        }

        @Override
        public String keep() {
            try {
                return storage.commit(staged);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            storage.discard(staged);
        }
    }
}
