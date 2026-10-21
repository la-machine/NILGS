// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * What a session may hold in the database: strings, numbers and booleans only.
 *
 * <p>Spring Session stores attribute values with Java serialization. Reading back bytes that
 * someone could have written into {@code spring_session_attributes} would let them run
 * "gadget" code during deserialization. Here the writer refuses any other type, and the
 * reader installs an {@link ObjectInputFilter} that rejects every class outside the
 * allow-list before it is instantiated, with tight size and depth limits. The session
 * therefore holds the user id, the authentication level and the login time, never an
 * object graph ({@code SessionSecurityContextRepository}).
 */
@Configuration(proxyBeanMethods = false)
public class SessionSerializationConfiguration {

    static final Set<Class<?>> ALLOWED = Set.of(String.class, Long.class, Integer.class, Boolean.class);

    /** java.lang.Number is the serialised superclass of Long and Integer. */
    static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "java.lang.String;java.lang.Long;java.lang.Integer;java.lang.Boolean;java.lang.Number;"
                    + "maxdepth=2;maxrefs=8;maxbytes=4096;maxarray=0;!*");

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> restrictedSessionSerialization() {
        GenericConversionService conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, SessionSerializationConfiguration::serialize);
        conversion.addConverter(byte[].class, Object.class, SessionSerializationConfiguration::deserialize);
        return repository -> repository.setConversionService(conversion);
    }

    static byte[] serialize(Object value) {
        if (value == null || !ALLOWED.contains(value.getClass())) {
            throw new IllegalArgumentException("session attributes hold only strings, numbers and booleans, not "
                    + (value == null ? "null" : value.getClass().getName()));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Larger values are refused before reading (the filter's maxbytes does not apply to plain strings). */
    static final int MAX_BYTES = 4096;

    static Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IllegalStateException("refused a session attribute larger than " + MAX_BYTES + " bytes");
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(FILTER);
            Object value = in.readObject();
            if (value == null || !ALLOWED.contains(value.getClass())) {
                throw new IllegalStateException("refused a session attribute of an unexpected type");
            }
            return value;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("refused a session attribute that is not a plain value", e);
        }
    }
}
