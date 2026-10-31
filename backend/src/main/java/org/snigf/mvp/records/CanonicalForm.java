// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.records;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The canonical encoding of a signed record: a version tag, then every field in a fixed order,
 * each as a big-endian four-byte length and its bytes. An absent field has the length -1, so
 * it differs from an empty one, and no two field lists encode to the same bytes. The database
 * encodes the same way (V21, {@code canonical_field}), and a parity test holds the two together.
 */
public final class CanonicalForm {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private CanonicalForm() {
    }

    public static CanonicalForm of(String versionTag) {
        return new CanonicalForm().text(versionTag);
    }

    public CanonicalForm text(String value) {
        return bytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    public CanonicalForm bytes(byte[] value) {
        bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : value.length).array());
        if (value != null) {
            bytes.writeBytes(value);
        }
        return this;
    }

    public byte[] toBytes() {
        return bytes.toByteArray();
    }
}
