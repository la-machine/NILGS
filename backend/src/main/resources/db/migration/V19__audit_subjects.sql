-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V19: audit subjects, for the case and parcel timeline (E-18, ADR-038, implementing ADR-027).
--
--   audit_event.subjects   what an event is about, as the database finds it from the record in
--                          the same transaction: [{"type": "APPLICATION", "id": ..., "office": ...}].
--                          A document version's and a payment's events name their application;
--                          an alert names both parcels, each with its office. The office is the
--                          record's at that moment (a draft has none); never the actor's.
--   audit_subjects()       finds them, as the owner of the tables (a system actor finds them too)
--   audit_canonical()      v2 for an event with subjects (the subjects are hashed); events
--                          without subjects keep v1 byte for byte, so every earlier hash and
--                          checkpoint still verifies. No event recorded before V19 is given
--                          subjects (ADR-027: no retrospective office).
--   audit_readable()       who reads an event: audit.event.search everywhere, or at the office of
--                          one of its subjects; an event without an office, everywhere only

SET search_path = snigf, public;

ALTER TABLE audit_event ADD COLUMN subjects jsonb
    CHECK (subjects IS NULL OR (jsonb_typeof(subjects) = 'array' AND jsonb_array_length(subjects) BETWEEN 1 AND 10));
CREATE INDEX ix_audit_subjects ON audit_event USING gin (subjects jsonb_path_ops);

-- One subject: its type and id, and its office when it has one.
CREATE FUNCTION audit_subject(p_type text, p_id uuid, p_office uuid) RETURNS jsonb
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_build_object('type', p_type, 'id', p_id)
           || CASE WHEN p_office IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('office', p_office) END
$$;

-- What an event about (p_type, p_id) is about; NULL for records that belong to no case or parcel.
CREATE FUNCTION audit_subjects(p_type text, p_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE
        WHEN p_id IS NULL THEN NULL
        WHEN p_type = 'APPLICATION' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id)) FROM application a WHERE a.id = p_id)
        WHEN p_type = 'DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM document_version v JOIN document d ON d.id = v.document_id JOIN application a ON a.id = d.application_id
              WHERE v.id = p_id)
        WHEN p_type = 'PAYMENT_REQUEST' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM payment_request pr JOIN application a ON a.id = pr.application_id WHERE pr.id = p_id)
        WHEN p_type = 'PAYMENT_RECORD' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM payment_record rec JOIN payment_request pr ON pr.id = rec.payment_request_id
               JOIN application a ON a.id = pr.application_id WHERE rec.id = p_id)
        WHEN p_type = 'PARCEL' THEN
            (SELECT jsonb_build_array(audit_subject('PARCEL', p.id, p.office_id)) FROM parcel p WHERE p.id = p_id)
        WHEN p_type = 'CONFLICT_ALERT' THEN
            (SELECT jsonb_agg(audit_subject('PARCEL', p.id, p.office_id) ORDER BY p.id)
               FROM conflict_alert c JOIN parcel p ON p.id IN (c.parcel_a_id, c.parcel_b_id) WHERE c.id = p_id)
        ELSE NULL
    END
$$;

-- Canonical form: v1 exactly as V3 for an event without subjects; v2, with them, otherwise.
CREATE OR REPLACE FUNCTION audit_canonical(e audit_event) RETURNS bytea
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT convert_to(concat_ws('|',
        CASE WHEN e.subjects IS NULL THEN 'snigf-audit-v1' ELSE 'snigf-audit-v2' END,
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
        audit_field(e.details::text),
        CASE WHEN e.subjects IS NULL THEN NULL ELSE audit_field(e.subjects::text) END
    ), 'UTF8');
$$;

-- The chain, as V3, with the subjects found from the record before the hash: never the caller's.
CREATE OR REPLACE FUNCTION audit_event_chain() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
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
    NEW.subjects    := audit_subjects(NEW.entity_type, NEW.entity_id);
    NEW.chain_pos   := last_pos + 1;
    NEW.occurred_at := clock_timestamp();
    NEW.prev_hash   := last_hash;
    NEW.hash        := sha256(NEW.prev_hash || audit_canonical(NEW));
    RETURN NEW;
END;
$$;

-- Whether the actor of the transaction may read an event with these subjects (ADR-038).
CREATE FUNCTION audit_readable(p_subjects jsonb) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT current_actor() IS NOT NULL
       AND (holds_permission_at(current_actor(), 'audit.event.search', NULL)
            OR holds_grant_at(current_actor(), 'audit.event.search', NULL)
            OR EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(p_subjects, '[]'::jsonb)) s
                        WHERE s ? 'office' AND holds_at(current_actor(), 'audit.event.search', (s ->> 'office')::uuid)))
$$;

REVOKE EXECUTE ON FUNCTION audit_subjects(text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_subjects(text, uuid) TO snigf_app;
