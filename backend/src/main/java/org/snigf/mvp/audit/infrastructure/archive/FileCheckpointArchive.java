// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.infrastructure.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snigf.mvp.audit.AuditEncoding;
import org.snigf.mvp.audit.Checkpoint;
import org.snigf.mvp.audit.application.AuditProperties;
import org.snigf.mvp.audit.application.CheckpointArchive;
import org.snigf.mvp.audit.domain.UnreadableCheckpoint;
import org.snigf.mvp.keys.Signature;
import org.snigf.mvp.keys.SigningPublicKey;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checkpoint directory: one {@code checkpoint-<pos>.json} per checkpoint, plus
 * {@value #KEYS_FILE} with the public keys. It is a volume of the app container only,
 * never the database's. Each file is flushed to disk, made read-only and renamed into
 * place, so a reader never sees a partial file; a checkpoint file is never replaced.
 */
@Component
public class FileCheckpointArchive implements CheckpointArchive {

    static final String KEYS_FILE = "checkpoint-keys.json";
    private static final Pattern NAME = Pattern.compile("checkpoint-(\\d{1,19})\\.json");
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("r--------");

    private final Path directory;
    private final JsonMapper json;

    public FileCheckpointArchive(AuditProperties properties, JsonMapper json) {
        this.directory = properties.checkpointDirectory().toAbsolutePath().normalize();
        this.json = json;
        prepare();
    }

    @Override
    public void store(Checkpoint checkpoint) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", AuditEncoding.CHECKPOINT_VERSION);
        document.put("id", checkpoint.id().toString());
        document.put("chainPos", checkpoint.chainPos());
        document.put("headHash", checkpoint.headHashHex());
        document.put("createdAt", AuditEncoding.TIMESTAMP.format(checkpoint.createdAt()));
        document.put("keyVersion", checkpoint.signature().keyVersion());
        document.put("signature", Base64.getEncoder().encodeToString(checkpoint.signature().value()));
        writeOnce(name(checkpoint.chainPos()), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
    }

    /** Keys only gain versions, so replacing the file only adds entries. */
    @Override
    public void storePublicKeys(List<SigningPublicKey> keys) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (SigningPublicKey key : keys) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", key.version());
            entry.put("ed25519", Base64.getEncoder().encodeToString(key.raw()));
            entry.put("pem", "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(key.toPublicKey().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            versions.add(entry);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("purpose", "SNIGF audit checkpoint signing (Ed25519)");
        document.put("keys", versions);
        replace(KEYS_FILE, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
    }

    @Override
    public Optional<byte[]> read(long chainPos) {
        Path file = directory.resolve(name(chainPos));
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read checkpoint file " + file.getFileName(), e);
        }
    }

    @Override
    public Contents readAll() {
        List<Checkpoint> checkpoints = new ArrayList<>();
        List<UnreadableCheckpoint> unreadable = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "checkpoint-*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.equals(KEYS_FILE)) {
                    continue;
                }
                Matcher m = NAME.matcher(name);
                long pos = m.matches() ? Long.parseLong(m.group(1)) : 0;
                try {
                    Checkpoint checkpoint = parse(Files.readAllBytes(file));
                    if (checkpoint.chainPos() != pos) {
                        unreadable.add(new UnreadableCheckpoint(name, pos, "file name does not match chainPos"));
                    } else {
                        checkpoints.add(checkpoint);
                    }
                } catch (IOException | RuntimeException e) {
                    unreadable.add(new UnreadableCheckpoint(name, pos, e.getClass().getSimpleName()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list the checkpoint directory", e);
        }
        return new Contents(checkpoints, unreadable);
    }

    Path directory() {
        return directory;
    }

    private Checkpoint parse(byte[] bytes) {
        JsonNode node = json.readTree(bytes);
        if (!AuditEncoding.CHECKPOINT_VERSION.equals(node.path("format").asString(""))) {
            throw new IllegalArgumentException("unknown format");
        }
        return new Checkpoint(
                UUID.fromString(node.path("id").asString("")),
                node.path("chainPos").asLong(0),
                HexFormat.of().parseHex(node.path("headHash").asString("")),
                Instant.from(AuditEncoding.TIMESTAMP.parse(node.path("createdAt").asString(""))),
                new Signature(node.path("keyVersion").asInt(0),
                        Base64.getDecoder().decode(node.path("signature").asString(""))));
    }

    private static String name(long chainPos) {
        return "checkpoint-" + chainPos + ".json";
    }

    private void writeOnce(String name, byte[] content) {
        Path target = directory.resolve(name);
        if (Files.exists(target)) {
            throw new IllegalStateException("checkpoint file " + name + " already exists; files are never replaced");
        }
        Path temp = writeTemp(name, content);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
            deleteQuietly(temp);
            throw new IllegalStateException("checkpoint file " + name + " already exists", e);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("cannot write checkpoint file " + name, e);
        }
    }

    private void replace(String name, byte[] content) {
        Path temp = writeTemp(name, content);
        try {
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("cannot write " + name, e);
        }
    }

    /** Writes, flushes to disk, then makes the file read-only. */
    private Path writeTemp(String name, byte[] content) {
        Path temp = directory.resolve("." + name + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("cannot write " + name, e);
        }
        setPermissions(temp, FILE);
        return temp;
    }

    /** Creates the directory if needed, restricts it to the owner, and checks it is writable. */
    private void prepare() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create the checkpoint directory " + directory, e);
        }
        setPermissions(directory, DIRECTORY);
        if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
            throw new IllegalStateException("the checkpoint directory " + directory + " is not writable");
        }
    }

    /** POSIX only; on a Windows development host the directory keeps the user's ACL. */
    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot restrict permissions of " + path.getFileName(), e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // best effort: a temporary name is never read
        }
    }
}
