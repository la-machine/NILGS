// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Files are kept under random keys, staged first, never larger than allowed, and found again for cleanup. */
class FileDocumentStorageTest {

    @TempDir
    Path root;

    FileDocumentStorage storage() throws IOException {
        return new FileDocumentStorage(new DocumentProperties(root, 1024, 3, 4_000_000, Duration.ofHours(1), "-"));
    }

    @Test
    void aStagedFileIsCountedHashedAndKeptUnderARandomKey() throws IOException {
        FileDocumentStorage storage = storage();
        DocumentStorage.Staged staged = storage.stage(new ByteArrayInputStream("abc".getBytes()), 1024);
        assertThat(staged.size()).isEqualTo(3);
        assertThat(staged.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String key = storage.commit(staged);
        assertThat(key).matches("^[0-9a-f]{32}$");
        assertThat(Files.exists(staged.file())).as("moved, not copied").isFalse();
        assertThat(Files.exists(root.resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key))).isTrue();
        try (InputStream in = storage.open(key)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("abc");
        }
        assertThat(storage.commit(storage.stage(new ByteArrayInputStream("abc".getBytes()), 1024)))
                .as("the same bytes get another key").isNotEqualTo(key);
    }

    @Test
    void anUploadLargerThanAllowedLeavesNothing() throws IOException {
        FileDocumentStorage storage = storage();
        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(new byte[1025]), 1024))
                .isInstanceOf(DocumentStorage.TooLarge.class);
        try (var staged = Files.list(root.resolve(".staging"))) {
            assertThat(staged).isEmpty();
        }
    }

    @Test
    void onlyKeyShapedPathsAreEverBuilt() throws IOException {
        FileDocumentStorage storage = storage();
        assertThatThrownBy(() -> storage.open("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("ABCDEF")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCleanupSeesOnlyOldKeptFilesAndPurgesOldStaging() throws IOException {
        FileDocumentStorage storage = storage();
        String old = storage.commit(storage.stage(new ByteArrayInputStream("old".getBytes()), 1024));
        String recent = storage.commit(storage.stage(new ByteArrayInputStream("new".getBytes()), 1024));
        Path oldFile = root.resolve(old.substring(0, 2)).resolve(old.substring(2, 4)).resolve(old);
        Files.setLastModifiedTime(oldFile, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Files.writeString(root.resolve("stray.txt"), "not a key");
        Path leftover = Files.writeString(root.resolve(".staging").resolve("upload-1.part"), "half");
        Files.setLastModifiedTime(leftover, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        Instant before = Instant.now().minus(Duration.ofHours(1));
        assertThat(storage.objectsOlderThan(before)).extracting(DocumentStorage.StoredObject::key).containsExactly(old);
        assertThat(storage.purgeStaging(before)).isEqualTo(1);
        storage.delete(old);
        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(storage.objectsOlderThan(Instant.now().plusSeconds(5))).extracting(DocumentStorage.StoredObject::key)
                .containsExactly(recent);
    }
}
