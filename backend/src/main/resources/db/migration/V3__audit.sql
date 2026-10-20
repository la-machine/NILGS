-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V3 — audit: append-only, hash-chained events and checkpoint records
-- (Slice 1 design §7, requirement C7).
--
-- How the chain works
--   * A BEFORE INSERT trigger takes a transaction-scoped advisory lock, reads the
--     last committed row, and assigns chain_pos = last + 1 and
--     hash = sha256(prev_hash || audit_canonical(row)).
--   * The lock is held until commit, so positions follow commit order and a
--     rolled-back insert leaves no gap. Audit writes are therefore serialised.
--   * The trigger relies on READ COMMITTED (each statement sees the latest
--     commits). Under REPEATABLE READ a stale read produces a duplicate chain_pos,
--     which the primary key rejects: the failure is safe, never a forked chain.
--   * The trigger overwrites chain_pos, occurred_at, prev_hash and hash, so the
--     client cannot choose them.
--
-- Residual risk (docs/audit.md): a superuser, or the schema owner
-- (snigf_migrator), can disable triggers and rewrite rows and hashes. That is
-- detectable only against signed checkpoints kept off-host.

CREATE TABLE audit_event (
    chain_pos       bigint PRIMARY KEY,
    id              uuid NOT NULL UNIQUE,
    occurred_at     timestamptz NOT NULL,
    actor_type      text NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'ANONYMOUS')),
    actor_id        uuid,
    actor_label     text CHECK (actor_label IS NULL OR length(actor_label) BETWEEN 1 AND 100),
    actor_roles     text[] NOT NULL DEFAULT '{}',
    action          text NOT NULL CHECK (action ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    entity_type     text CHECK (entity_type IS NULL OR entity_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    entity_id       uuid,
    entity_ref      text CHECK (entity_ref IS NULL OR length(entity_ref) <= 64),
    outcome         text NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    reason          text CHECK (reason IS NULL OR length(reason) <= 1000),
    correlation_id  uuid NOT NULL,
    source_ip       inet,
    details         jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object'),
    prev_hash       bytea NOT NULL CHECK (octet_length(prev_hash) = 32),
    hash            bytea NOT NULL CHECK (octet_length(hash) = 32),
    -- The recorded actor must be coherent with its type.
    CONSTRAINT ck_actor CHECK (
        (actor_type = 'USER'      AND actor_id IS NOT NULL) OR
        (actor_type = 'SERVICE'   AND actor_id IS NULL AND actor_label IS NOT NULL) OR
        (actor_type = 'ANONYMOUS' AND actor_id IS NULL))
);
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id, chain_pos);
CREATE INDEX ix_audit_actor  ON audit_event (actor_id, chain_pos);
CREATE INDEX ix_audit_time   ON audit_event (occurred_at);

-- Canonical byte encoding of an event, excluding prev_hash and hash.
-- Every field is length-prefixed ("<utf8-byte-length>:<value>", or "~" for NULL)
-- and fields are joined with "|", so no value can be confused with a separator.
-- The Java verifier (ChainVerifier) implements the same encoding; a test asserts
-- both produce identical hashes.
CREATE FUNCTION audit_field(v text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN v IS NULL THEN '~' ELSE octet_length(v)::text || ':' || v END;
$$;

CREATE FUNCTION audit_canonical(e audit_event) RETURNS bytea
LANGUAGE sql STABLE AS $$
    SELECT convert_to(concat_ws('|',
        'snigf-audit-v1',
        audit_field(e.chain_pos::text),
        audit_field(e.id::text),
        audit_field(to_char(e.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
        audit_field(e.actor_type),
        audit_field(e.actor_id::text),
        audit_field(e.actor_label),
        audit_field(array_to_json(e.actor_roles)::text),
        audit_field(e.action),
        audit_field(e.entity_type),
        audit_field(e.entity_id::text),
        audit_field(e.entity_ref),
        audit_field(e.outcome),
        audit_field(e.reason),
        audit_field(e.correlation_id::text),
        audit_field(host(e.source_ip)),
        audit_field(e.details::text)
    ), 'UTF8');
$$;

CREATE FUNCTION audit_event_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    last_pos   bigint;
    last_hash  bytea;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('snigf.audit_event.chain', 0));
    SELECT chain_pos, hash INTO last_pos, last_hash
      FROM audit_event ORDER BY chain_pos DESC LIMIT 1;
    IF last_pos IS NULL THEN
        last_pos := 0;
        last_hash := decode(repeat('00', 32), 'hex');   -- genesis
    END IF;
    NEW.chain_pos   := last_pos + 1;
    NEW.occurred_at := clock_timestamp();
    NEW.prev_hash   := last_hash;
    NEW.hash        := sha256(NEW.prev_hash || audit_canonical(NEW));
    RETURN NEW;
END;
$$;
CREATE TRIGGER audit_event_chain BEFORE INSERT ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_chain();
CREATE TRIGGER audit_event_no_change BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Signed checkpoints. The signature is produced by the application with a key
-- the database never sees; this table is a convenience copy, the authoritative
-- copies are the signed files held off-host (Slice 1 design §7).
CREATE TABLE audit_checkpoint (
    id          uuid PRIMARY KEY,
    chain_pos   bigint NOT NULL REFERENCES audit_event (chain_pos),
    head_hash   bytea NOT NULL CHECK (octet_length(head_hash) = 32),
    created_at  timestamptz NOT NULL DEFAULT now(),
    signature   bytea NOT NULL,
    key_id      text NOT NULL CHECK (length(key_id) BETWEEN 1 AND 100)
);
CREATE TRIGGER audit_checkpoint_no_change BEFORE UPDATE OR DELETE ON audit_checkpoint
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER audit_checkpoint_no_truncate BEFORE TRUNCATE ON audit_checkpoint
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Recomputes the chain between two positions and reports the first break.
CREATE FUNCTION audit_verify_chain(p_from bigint DEFAULT 1, p_to bigint DEFAULT NULL)
RETURNS TABLE (verified boolean, checked_from bigint, checked_to bigint,
               first_mismatch_pos bigint, mismatch_kind text, rows_checked bigint)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    e             audit_event;
    expected_pos  bigint;
    expected_prev bytea;
    n             bigint := 0;
BEGIN
    checked_from := greatest(coalesce(p_from, 1), 1);
    expected_pos := checked_from;
    IF checked_from = 1 THEN
        expected_prev := decode(repeat('00', 32), 'hex');
    ELSE
        SELECT a.hash INTO expected_prev FROM audit_event a WHERE a.chain_pos = checked_from - 1;
    END IF;

    FOR e IN SELECT * FROM audit_event a
              WHERE a.chain_pos >= checked_from AND (p_to IS NULL OR a.chain_pos <= p_to)
              ORDER BY a.chain_pos
    LOOP
        n := n + 1;
        IF e.chain_pos <> expected_pos THEN
            RETURN QUERY SELECT false, checked_from, e.chain_pos, expected_pos, 'GAP'::text, n;
            RETURN;
        END IF;
        IF expected_prev IS NULL OR e.prev_hash <> expected_prev THEN
            RETURN QUERY SELECT false, checked_from, e.chain_pos, e.chain_pos, 'PREV_HASH'::text, n;
            RETURN;
        END IF;
        IF e.hash <> sha256(e.prev_hash || audit_canonical(e)) THEN
            RETURN QUERY SELECT false, checked_from, e.chain_pos, e.chain_pos, 'CONTENT_HASH'::text, n;
            RETURN;
        END IF;
        expected_prev := e.hash;
        expected_pos := expected_pos + 1;
    END LOOP;

    RETURN QUERY SELECT true, checked_from, expected_pos - 1, NULL::bigint, NULL::text, n;
END;
$$;

-- Grants: the application may append and read; it can never update or delete.
REVOKE EXECUTE ON FUNCTION audit_verify_chain(bigint, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_verify_chain(bigint, bigint) TO snigf_app, snigf_auditor;
GRANT SELECT, INSERT ON audit_event TO snigf_app;
GRANT SELECT, INSERT ON audit_checkpoint TO snigf_app;
GRANT SELECT ON audit_event, audit_checkpoint TO snigf_auditor;
