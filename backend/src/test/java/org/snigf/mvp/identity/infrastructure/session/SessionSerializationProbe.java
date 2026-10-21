// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.session;

/** Test access to the package-private converters of {@link SessionSerializationConfiguration}. */
public final class SessionSerializationProbe {

    private SessionSerializationProbe() {
    }

    public static byte[] serialize(Object value) {
        return SessionSerializationConfiguration.serialize(value);
    }

    public static Object deserialize(byte[] bytes) {
        return SessionSerializationConfiguration.deserialize(bytes);
    }

    public static Object roundTrip(Object value) {
        return deserialize(serialize(value));
    }
}
