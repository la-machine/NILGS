-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V5: server-side sessions (ADR-004), Spring Session JDBC's standard PostgreSQL schema
-- (spring-session-jdbc schema-postgresql.sql), created here because the application role
-- cannot run DDL (spring.session.jdbc.initialize-schema=never).
--
-- A session row holds no secret of its own; the attributes hold only strings and numbers
-- (user id, authentication level and time), and the application refuses to deserialise
-- anything else (SessionSerializationConfiguration). The session id in SESSION_ID is the
-- cookie value: whoever can read this table can use a live session until it expires, so
-- read access is limited to the application role.

CREATE TABLE spring_session (
    primary_id             char(36) NOT NULL,
    session_id             char(36) NOT NULL,
    creation_time          bigint NOT NULL,
    last_access_time       bigint NOT NULL,
    max_inactive_interval  int NOT NULL,
    expiry_time            bigint NOT NULL,
    principal_name         varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  char(36) NOT NULL,
    attribute_name      varchar(200) NOT NULL,
    attribute_bytes     bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Sessions are created, refreshed, expired and revoked by the application only.
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO snigf_app;
