// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.keys.Signature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision D5: an archived checkpoint file is never overwritten or replaced, by a later
 * checkpoint at the same position or by two writers racing; nothing is left behind by a
 * refused write.
 */
class FileCheckpointArchiveTest {

    @TempDir
    Path directory;

    private FileCheckpointArchive archive() {
        return new FileCheckpointArchive(new AuditProperties(directory, "0 0 2 * * *", "0 30 2 * * *",
                ZoneId.of("Africa/Douala"), true, 8192, 1000), JsonMapper.builder().build());
    }

    private static Checkpoint checkpoint(long pos, byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return new Checkpoint(UUID.randomUUID(), pos, hash, Instant.parse("2026-10-22T08:00:00Z"),
                new Signature(1, new byte[] {fill, fill, fill}));
    }

    @Test
    void aCheckpointFileIsNeverReplaced() throws Exception {
        FileCheckpointArchive archive = archive();
        archive.store(checkpoint(42, (byte) 1));
        byte[] original = Files.readAllBytes(directory.resolve("checkpoint-42.json"));

        assertThatThrownBy(() -> archive.store(checkpoint(42, (byte) 2)))
                .hasMessageContaining("already exists; files are never replaced");

        assertThat(Files.readAllBytes(directory.resolve("checkpoint-42.json"))).isEqualTo(original);
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("checkpoint-42.json");
        }
    }

    @Test
    void racingWritersCannotReplaceEachOther() throws Exception {
        FileCheckpointArchive archive = archive();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> writers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            byte fill = (byte) (i + 1);
            writers.add(() -> {
                start.await();
                try {
                    archive.store(checkpoint(7, fill));
                    return true;
                } catch (IllegalStateException refused) {
                    return false;
                }
            });
        }
        int written = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(writers.size())) {
            List<Future<Boolean>> results = writers.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Boolean> result : results) {
                written += result.get() ? 1 : 0;
            }
        }
        assertThat(written).as("exactly one writer publishes the file").isEqualTo(1);
        assertThat(archive.read(7)).isPresent();
        var contents = archive.readAll();
        assertThat(contents.unreadable()).as("no partial or mixed file").isEmpty();
        assertThat(contents.checkpoints()).hasSize(1);
        assertThat(contents.checkpoints().getFirst().chainPos()).isEqualTo(7);
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).as("no temporary file left")
                    .containsExactly("checkpoint-7.json");
        }
    }
}
