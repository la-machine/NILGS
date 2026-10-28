// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.application.DocumentStorage;
import org.springframework.stereotype.Component;

/**
 * Document bytes on a filesystem (the MVP adapter; S3-API storage comes with the pilot, P-02).
 * Layout under {@code SNIGF_DOCUMENT_DIRECTORY}: {@code .staging/} for uploads being checked,
 * {@code ab/cd/abcd…} for kept files, named by a random 128-bit key, never by the name the
 * person gave. Kept files are written once (atomic move) and made read-only; directories and
 * files are the application user's alone where the filesystem supports POSIX permissions.
 * A key is checked against its pattern before any path is built, so no input reaches a path.
 */
@Component
class FileDocumentStorage implements DocumentStorage {

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{32}$");
    private static final String STAGING = ".staging";

    private final Path root;
    private final Path staging;
    private final SecureRandom random = new SecureRandom();
    private final boolean posix;

    FileDocumentStorage(DocumentProperties settings) throws IOException {
        this.root = settings.directory().toAbsolutePath().normalize();
        this.staging = root.resolve(STAGING);
        this.posix = Files.getFileStore(Files.createDirectories(root)).supportsFileAttributeView("posix");
        Files.createDirectories(staging);
        privateDirectory(root);
        privateDirectory(staging);
        if (!Files.isWritable(root) || !Files.isWritable(staging)) {
            throw new IllegalStateException("Document directory is not writable: " + root);
        }
    }

    @Override
    public Staged stage(InputStream content, long maxBytes) throws IOException {
        Path file = Files.createTempFile(staging, "upload-", ".part");
        try {
            MessageDigest digest = sha256();
            long size = 0;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest)) {
                int read;
                while ((read = content.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw new TooLarge();
                    }
                    out.write(buffer, 0, read);
                }
            }
            return new Staged(file, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    @Override
    public String commit(Staged staged) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            String key = HexFormat.of().formatHex(bytes);
            Path target = path(key);
            Files.createDirectories(target.getParent());
            privateDirectory(target.getParent().getParent());
            privateDirectory(target.getParent());
            try {
                Files.move(staged.file(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            if (posix) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--------"));
            } else {
                target.toFile().setWritable(false);
            }
            return key;
        }
        throw new IOException("no free storage key after three attempts");
    }

    @Override
    public void discard(Staged staged) {
        try {
            Files.deleteIfExists(staged.file());
        } catch (IOException ignored) {
            // Removed by the cleanup once old enough.
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Path file = path(key);
        if (Files.exists(file)) {
            file.toFile().setWritable(true);
            Files.delete(file);
        }
    }

    @Override
    public List<StoredObject> objectsOlderThan(Instant before) throws IOException {
        List<StoredObject> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 3)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (file.startsWith(staging) || !Files.isRegularFile(file) || !KEY.matcher(name).matches()
                        || !file.equals(path(name))) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                if (modified.isBefore(before)) {
                    found.add(new StoredObject(name, modified));
                }
            }
        }
        return found;
    }

    @Override
    public int purgeStaging(Instant before) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(staging)) {
            for (Path file : files) {
                if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toInstant().isBefore(before)) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        }
        return removed;
    }

    private Path path(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("not a storage key");
        }
        return root.resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key);
    }

    private void privateDirectory(Path directory) throws IOException {
        if (posix) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
