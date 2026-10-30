-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V20: documents issued by SNIGF and their verification (Slice 1, ADR-039). DEMO only: the first
-- and only type is the application-submission acknowledgment, which establishes no right.
--
--   issued_document            the registry: a non-guessable number, type, template, the subject
--                              (the application), the office, to whom, when, the SHA-256 of the
--                              exact PDF, the signed manifest (Ed25519, OpenBao) and its key
--                              version, the status. Never edited; the status only moves forward
--                              from VALID (to SUPERSEDED, REVOKED or CANCELLED), once, by a holder
--                              of document.issued.manage who is not its holder
--   issued_document_artifact   the exact PDF bytes, whose SHA-256 the database checks against the
--                              registry
--   document_verification      every verification attempt, append-only
--   verification_incident      a suspicious verification for review; resolved once, with a note,
--                              by a holder of document.incident.review
--
-- One issuance per operation (operation_key unique): retries and concurrent attempts yield one
-- record. Public verification reads through SECURITY DEFINER lookups that return nothing personal.
-- Uploaded documents (V14) are a different thing and are not touched.

SET search_path = snigf, public;

-- --- Permissions and the document officer -------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('document.issued.manage', 'BUSINESS', true, ARRAY['ANY'],
     'Revoke, cancel or re-issue a document issued by SNIGF', 'Révoquer, annuler ou rééditer un document émis par SNIGF'),
    ('document.incident.review', 'BUSINESS', true, ARRAY['ANY'],
     'Review document verification incidents', 'Examiner les incidents de vérification de documents');
INSERT INTO role_definition (code, name_fr, name_en, system, assignable) VALUES
    ('DOCUMENT_OFFICER', 'Agent de vérification documentaire', 'Document verification officer', true, true);
-- Baseline roles change only here, reviewed, never at run time.
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('DOCUMENT_OFFICER', 'document.issued.manage', 'ANY'),
    ('DOCUMENT_OFFICER', 'document.incident.review', 'ANY');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- A permission held everywhere, by a role or by a special grant without an office.
CREATE FUNCTION holds_everywhere(p_user uuid, p_permission text) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_user IS NOT NULL
       AND (holds_permission_at(p_user, p_permission, NULL) OR holds_grant_at(p_user, p_permission, NULL))
$$;

-- --- The registry -----------------------------------------------------------------------------------

CREATE TABLE issued_document (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    number             text NOT NULL UNIQUE CHECK (number ~ '^SNIGF-[0-9A-HJKMNP-TV-Z]{26}$'),
    doc_type           text NOT NULL CHECK (doc_type IN ('DEMO_SUBMISSION_ACKNOWLEDGMENT')),
    template_version   text NOT NULL CHECK (template_version ~ '^[a-z0-9.-]{1,40}$'),
    operation_key      text NOT NULL UNIQUE CHECK (length(operation_key) BETWEEN 1 AND 200),
    subject_type       text NOT NULL CHECK (subject_type IN ('APPLICATION')),
    subject_id         uuid NOT NULL REFERENCES application (id),
    subject_ref        text NOT NULL CHECK (length(subject_ref) BETWEEN 1 AND 64),
    office_id          uuid REFERENCES office (id),
    issued_to          uuid NOT NULL REFERENCES app_user (id),
    issued_at          timestamptz NOT NULL,
    -- What the document prints, kept so a re-issue prints the same facts (protected like the case).
    printed            jsonb NOT NULL CHECK (jsonb_typeof(printed) = 'object'),
    content_sha256     bytea NOT NULL CHECK (octet_length(content_sha256) = 32),
    manifest           bytea NOT NULL CHECK (octet_length(manifest) BETWEEN 1 AND 4096),
    signature          text NOT NULL CHECK (signature ~ '^snigf:v[0-9]+:[A-Za-z0-9+/=]+$'),
    key_version        int NOT NULL CHECK (key_version >= 1),
    demo               boolean NOT NULL DEFAULT true CHECK (demo),
    status             text NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID', 'SUPERSEDED', 'REVOKED', 'CANCELLED')),
    status_reason      text CHECK (status_reason IS NULL OR length(btrim(status_reason)) BETWEEN 10 AND 1000),
    status_changed_by  uuid REFERENCES app_user (id),
    status_changed_at  timestamptz,
    supersedes         uuid UNIQUE REFERENCES issued_document (id),
    superseded_by      uuid UNIQUE REFERENCES issued_document (id),
    CONSTRAINT ck_issued_status CHECK ((status = 'VALID') = (status_changed_at IS NULL)
                                       AND (status = 'VALID') = (status_reason IS NULL)
                                       AND (status = 'SUPERSEDED') = (superseded_by IS NOT NULL))
);
CREATE INDEX ix_issued_document_subject ON issued_document (subject_id, issued_at);
CREATE INDEX ix_issued_document_sha ON issued_document (content_sha256);

CREATE FUNCTION issued_document_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    previous issued_document;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'VALID' OR NEW.status_changed_by IS NOT NULL OR NEW.superseded_by IS NOT NULL THEN
            RAISE EXCEPTION 'a document is issued valid' USING ERRCODE = 'check_violation';
        END IF;
        -- The issuance time is signed in the manifest, so it comes from the application: within a minute of now.
        IF abs(extract(epoch FROM clock_timestamp() - NEW.issued_at)) > 60 THEN
            RAISE EXCEPTION 'issued_at must be the time of issuance' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.supersedes IS NOT NULL THEN
            SELECT * INTO previous FROM issued_document WHERE id = NEW.supersedes;
            IF previous.id IS NULL OR previous.status <> 'VALID' OR previous.subject_id <> NEW.subject_id
               OR previous.doc_type <> NEW.doc_type OR previous.issued_to <> NEW.issued_to THEN
                RAISE EXCEPTION 'only a valid document of the same type and subject is re-issued' USING ERRCODE = 'check_violation';
            END IF;
            IF NOT holds_everywhere(current_actor(), 'document.issued.manage') OR current_actor() = previous.issued_to THEN
                RAISE EXCEPTION 're-issuing needs document.issued.manage, never by its holder' USING ERRCODE = 'insufficient_privilege';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF (NEW.id, NEW.number, NEW.doc_type, NEW.template_version, NEW.operation_key, NEW.subject_type, NEW.subject_id,
        NEW.subject_ref, NEW.office_id, NEW.issued_to, NEW.issued_at, NEW.printed, NEW.content_sha256, NEW.manifest,
        NEW.signature, NEW.key_version, NEW.demo, NEW.supersedes)
       IS DISTINCT FROM
       (OLD.id, OLD.number, OLD.doc_type, OLD.template_version, OLD.operation_key, OLD.subject_type, OLD.subject_id,
        OLD.subject_ref, OLD.office_id, OLD.issued_to, OLD.issued_at, OLD.printed, OLD.content_sha256, OLD.manifest,
        OLD.signature, OLD.key_version, OLD.demo, OLD.supersedes) THEN
        RAISE EXCEPTION 'what was issued never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'VALID' THEN
        RAISE EXCEPTION 'document % is no longer valid: its status is final', OLD.number USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status = 'VALID' THEN
        RAISE EXCEPTION 'a status change leaves VALID' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status_changed_by IS DISTINCT FROM current_actor() OR current_actor() IS NULL
       OR NOT holds_everywhere(current_actor(), 'document.issued.manage') THEN
        RAISE EXCEPTION 'changing a document''s status needs document.issued.manage' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF current_actor() = OLD.issued_to THEN
        RAISE EXCEPTION 'nobody changes the status of a document issued to themselves' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.status = 'SUPERSEDED' AND NOT EXISTS (SELECT 1 FROM issued_document r WHERE r.id = NEW.superseded_by
                                                    AND r.supersedes = OLD.id) THEN
        RAISE EXCEPTION 'a superseded document names the document that replaces it' USING ERRCODE = 'check_violation';
    END IF;
    NEW.status_changed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER issued_document_guard BEFORE INSERT OR UPDATE ON issued_document
    FOR EACH ROW EXECUTE FUNCTION issued_document_guard();
CREATE TRIGGER issued_document_no_delete BEFORE DELETE ON issued_document
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER issued_document_no_truncate BEFORE TRUNCATE ON issued_document
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE issued_document_artifact (
    document_id  uuid PRIMARY KEY REFERENCES issued_document (id),
    media_type   text NOT NULL CHECK (media_type = 'application/pdf'),
    bytes        bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 2097152)
);

-- The stored bytes are those the registry fingerprinted, or nothing is stored.
CREATE FUNCTION issued_document_artifact_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM issued_document d WHERE d.id = NEW.document_id AND d.content_sha256 = sha256(NEW.bytes)) THEN
        RAISE EXCEPTION 'the artifact does not match its registered fingerprint' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER issued_document_artifact_guard BEFORE INSERT ON issued_document_artifact
    FOR EACH ROW EXECUTE FUNCTION issued_document_artifact_guard();
CREATE TRIGGER issued_document_artifact_append_only BEFORE UPDATE OR DELETE ON issued_document_artifact
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER issued_document_artifact_no_truncate BEFORE TRUNCATE ON issued_document_artifact
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Verification and incidents ---------------------------------------------------------------------

CREATE TABLE document_verification (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    at                  timestamptz NOT NULL DEFAULT clock_timestamp(),
    channel             text NOT NULL CHECK (channel IN ('NUMBER', 'FILE')),
    claimed_number      text CHECK (claimed_number IS NULL OR length(claimed_number) <= 64),
    presented_sha256    bytea CHECK (presented_sha256 IS NULL OR octet_length(presented_sha256) = 32),
    issued_document_id  uuid REFERENCES issued_document (id),
    outcome             text NOT NULL CHECK (outcome IN ('AUTHENTIC', 'RECORD_FOUND', 'ALTERED', 'UNVERIFIED',
                                                         'REVOKED', 'SUPERSEDED', 'CANCELLED')),
    reason              text NOT NULL CHECK (reason ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    actor_id            uuid REFERENCES app_user (id),
    CONSTRAINT ck_verification_channel CHECK ((channel = 'FILE') = (presented_sha256 IS NOT NULL))
);
CREATE INDEX ix_document_verification_document ON document_verification (issued_document_id, at);
CREATE TRIGGER document_verification_append_only BEFORE UPDATE OR DELETE ON document_verification
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER document_verification_no_truncate BEFORE TRUNCATE ON document_verification
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE TABLE verification_incident (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    verification_id     uuid NOT NULL UNIQUE REFERENCES document_verification (id),
    kind                text NOT NULL CHECK (kind IN ('ALTERED', 'FABRICATED', 'REGISTRY_INTEGRITY')),
    issued_document_id  uuid REFERENCES issued_document (id),
    opened_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    status              text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution          text CHECK (resolution IN ('CONFIRMED_ALTERATION', 'GENUINE_COPY', 'NOT_A_SNIGF_DOCUMENT', 'REFERRED')),
    note                text CHECK (note IS NULL OR length(btrim(note)) BETWEEN 10 AND 2000),
    resolved_by         uuid REFERENCES app_user (id),
    resolved_at         timestamptz,
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_resolution CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL)
                                             AND (status = 'RESOLVED') = (note IS NOT NULL)
                                             AND (status = 'RESOLVED') = (resolved_by IS NOT NULL))
);
CREATE INDEX ix_verification_incident_open ON verification_incident (opened_at) WHERE status = 'OPEN';

CREATE FUNCTION verification_incident_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.resolved_by IS NOT NULL THEN
            RAISE EXCEPTION 'an incident is opened' USING ERRCODE = 'check_violation';
        END IF;
        NEW.opened_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.verification_id, NEW.kind, NEW.issued_document_id, NEW.opened_at)
       IS DISTINCT FROM (OLD.id, OLD.verification_id, OLD.kind, OLD.issued_document_id, OLD.opened_at) THEN
        RAISE EXCEPTION 'what an incident is about never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'OPEN' OR NEW.status <> 'RESOLVED' OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'an open incident is resolved once' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.resolved_by IS DISTINCT FROM current_actor() OR NOT holds_everywhere(current_actor(), 'document.incident.review') THEN
        RAISE EXCEPTION 'resolving needs document.incident.review' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF EXISTS (SELECT 1 FROM issued_document d WHERE d.id = OLD.issued_document_id AND d.issued_to = current_actor()) THEN
        RAISE EXCEPTION 'nobody resolves an incident about their own document' USING ERRCODE = 'insufficient_privilege';
    END IF;
    NEW.resolved_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER verification_incident_guard BEFORE INSERT OR UPDATE ON verification_incident
    FOR EACH ROW EXECUTE FUNCTION verification_incident_guard();
CREATE TRIGGER verification_incident_no_delete BEFORE DELETE ON verification_incident
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER verification_incident_no_truncate BEFORE TRUNCATE ON verification_incident
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Lookups for verification (no actor: the public page), never anything personal ------------------

-- What verifying needs, by number or by fingerprint: the evidence, the status, the issuing office, the
-- replacement. The subject reference is returned to rebuild the signed manifest; the application
-- never shows it to the public.
CREATE FUNCTION issued_document_evidence(p_number text, p_sha256 bytea)
RETURNS TABLE (id uuid, number text, doc_type text, template_version text, subject_type text, subject_ref text,
               issued_at timestamptz, office_code text, office_name_fr text, office_name_en text, content_sha256 bytea,
               manifest bytea, signature text, key_version int, status text, replacement_number text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT d.id, d.number, d.doc_type, d.template_version, d.subject_type, d.subject_ref, d.issued_at, o.code, o.name_fr, o.name_en,
           d.content_sha256, d.manifest, d.signature, d.key_version, d.status, r.number
      FROM issued_document d
      LEFT JOIN office o ON o.id = d.office_id
      LEFT JOIN issued_document r ON r.id = d.superseded_by
     WHERE (p_number IS NOT NULL AND d.number = p_number)
        OR (p_number IS NULL AND p_sha256 IS NOT NULL AND d.content_sha256 = p_sha256)
     ORDER BY d.issued_at
     LIMIT 1
$$;

-- --- The audit subjects: issued documents and their incidents belong to their application -------------

CREATE OR REPLACE FUNCTION audit_subjects(p_type text, p_id uuid) RETURNS jsonb
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
        WHEN p_type = 'ISSUED_DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM issued_document d JOIN application a ON a.id = d.subject_id WHERE d.id = p_id)
        WHEN p_type = 'VERIFICATION_INCIDENT' THEN
            (SELECT jsonb_build_array(audit_subject('APPLICATION', a.id, a.office_id))
               FROM verification_incident i JOIN issued_document d ON d.id = i.issued_document_id
               JOIN application a ON a.id = d.subject_id WHERE i.id = p_id)
        WHEN p_type = 'PARCEL' THEN
            (SELECT jsonb_build_array(audit_subject('PARCEL', p.id, p.office_id)) FROM parcel p WHERE p.id = p_id)
        WHEN p_type = 'CONFLICT_ALERT' THEN
            (SELECT jsonb_agg(audit_subject('PARCEL', p.id, p.office_id) ORDER BY p.id)
               FROM conflict_alert c JOIN parcel p ON p.id IN (c.parcel_a_id, c.parcel_b_id) WHERE c.id = p_id)
        ELSE NULL
    END
$$;

-- --- Row-level security ------------------------------------------------------------------------------

ALTER TABLE issued_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE issued_document_artifact ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_verification ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_incident ENABLE ROW LEVEL SECURITY;

-- Whoever sees the application (its applicant, staff of its office) sees what was issued for it;
-- the document officer sees every issued document.
CREATE POLICY issued_document_read ON issued_document FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = subject_id)
           OR holds_everywhere(current_actor(), 'document.issued.manage'));
-- The first issuance is for the applicant, as the applicant; a re-issue is the document officer's
-- (the guard then checks it replaces a valid document of the same subject and holder).
CREATE POLICY issued_document_issue ON issued_document FOR INSERT TO snigf_app
    WITH CHECK ((issued_to = current_actor()
                 AND EXISTS (SELECT 1 FROM application a WHERE a.id = subject_id AND a.applicant_id = issued_to))
                OR (supersedes IS NOT NULL AND holds_everywhere(current_actor(), 'document.issued.manage')));
CREATE POLICY issued_document_status ON issued_document FOR UPDATE TO snigf_app
    USING (holds_everywhere(current_actor(), 'document.issued.manage'))
    WITH CHECK (holds_everywhere(current_actor(), 'document.issued.manage'));
CREATE POLICY issued_document_artifact_read ON issued_document_artifact FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM issued_document d WHERE d.id = document_id));
CREATE POLICY issued_document_artifact_store ON issued_document_artifact FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM issued_document d WHERE d.id = document_id));
-- Anyone may be recorded verifying (the public page); only reviewers read the record.
CREATE POLICY document_verification_record ON document_verification FOR INSERT TO snigf_app
    WITH CHECK (actor_id IS NOT DISTINCT FROM current_actor());
CREATE POLICY document_verification_read ON document_verification FOR SELECT TO snigf_app
    USING (holds_everywhere(current_actor(), 'document.incident.review'));
CREATE POLICY verification_incident_open ON verification_incident FOR INSERT TO snigf_app
    WITH CHECK (true);
CREATE POLICY verification_incident_read ON verification_incident FOR SELECT TO snigf_app
    USING (holds_everywhere(current_actor(), 'document.incident.review'));
CREATE POLICY verification_incident_resolve ON verification_incident FOR UPDATE TO snigf_app
    USING (holds_everywhere(current_actor(), 'document.incident.review'))
    WITH CHECK (holds_everywhere(current_actor(), 'document.incident.review'));

-- --- Grants ---------------------------------------------------------------------------------------------

REVOKE ALL ON issued_document, issued_document_artifact, document_verification, verification_incident FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION issued_document_evidence(text, bytea) FROM PUBLIC;
GRANT SELECT, INSERT ON issued_document TO snigf_app;
GRANT UPDATE (status, status_reason, status_changed_by, status_changed_at, superseded_by) ON issued_document TO snigf_app;
GRANT SELECT, INSERT ON issued_document_artifact TO snigf_app;
GRANT SELECT, INSERT ON document_verification TO snigf_app;
GRANT SELECT, INSERT ON verification_incident TO snigf_app;
GRANT UPDATE (status, resolution, note, resolved_by, resolved_at, version) ON verification_incident TO snigf_app;
GRANT EXECUTE ON FUNCTION issued_document_evidence(text, bytea) TO snigf_app;
