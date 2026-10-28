-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V14: supporting documents of an application (MVP-02, E-13, ADR-033).
--
--   document          one per application and category of its type (DEMO_IDENTITY ...)
--   document_version  each file uploaded for it: storage key (random, never the name),
--                     SHA-256, size, the type read from its bytes, the name as given (for
--                     display only), status; numbered by the database; never changed
--
-- Zero trust, in the database:
--   * row-level security: a document is visible exactly when its application is (V13: the
--     owner, or staff reading at the office), and only the owner adds one;
--   * a file is added only to the owner's application while it accepts documents (a draft;
--     information requested comes with E-14), only in a category of its type version;
--   * a stored version always has its key, hash, size and an allowed type; the content
--     itself lives outside the database, and the hash proves it unchanged;
--   * nothing is updated or deleted, except together with a draft its owner deletes.

SET search_path = snigf, public;

CREATE TABLE document (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    category        text NOT NULL CHECK (category ~ '^DEMO_[A-Z0-9_]{1,40}$'),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (application_id, category)
);

CREATE TABLE document_version (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    document_id     uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    version_no      int NOT NULL CHECK (version_no >= 1),
    -- PENDING is for a scan still running (pilot, P-02); REJECTED for a file a scan refused.
    status          text NOT NULL CHECK (status IN ('PENDING', 'STORED', 'REJECTED')),
    storage_key     char(32) UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256          char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes      bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type   text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name   text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200
                                         AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by     uuid NOT NULL REFERENCES app_user (id),
    uploaded_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (document_id, version_no),
    CONSTRAINT ck_document_stored CHECK (status <> 'STORED' OR storage_key IS NOT NULL)
);
CREATE INDEX ix_document_version_document ON document_version (document_id, version_no DESC);

-- --- Guards ----------------------------------------------------------------------------------

-- Whether the application takes documents now, and in this category, from this person.
CREATE FUNCTION document_accepted(p_application uuid, p_category text, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1
          FROM application a
          JOIN application_type t ON t.id = a.type_version_id
         WHERE a.id = p_application
           AND a.applicant_id = p_actor
           AND a.state = 'DRAFT'
           AND t.requirements -> 'documents' @> jsonb_build_array(jsonb_build_object('code', p_category)))
$$;

CREATE FUNCTION document_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT document_accepted(NEW.application_id, NEW.category, current_actor()) THEN
        RAISE EXCEPTION 'application % does not take a % document from this person now', NEW.application_id, NEW.category
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    NEW.created_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_guard BEFORE INSERT ON document
    FOR EACH ROW EXECUTE FUNCTION document_guard();

CREATE FUNCTION document_version_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    d document;
BEGIN
    SELECT * INTO d FROM document WHERE id = NEW.document_id;
    IF d.id IS NULL OR NOT document_accepted(d.application_id, d.category, current_actor())
       OR NEW.uploaded_by IS DISTINCT FROM current_actor() THEN
        RAISE EXCEPTION 'this document does not take a new version from this person now'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Numbered here, one after the other, whatever the application asked.
    PERFORM pg_advisory_xact_lock(hashtext('snigf.document:' || NEW.document_id));
    NEW.version_no := coalesce((SELECT max(version_no) FROM document_version WHERE document_id = NEW.document_id), 0) + 1;
    NEW.uploaded_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_version_guard BEFORE INSERT ON document_version
    FOR EACH ROW EXECUTE FUNCTION document_version_guard();

-- Kept as recorded: only a scan's verdict may move PENDING on (pilot); nothing else changes.
CREATE FUNCTION document_version_immutable() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF OLD.status = 'PENDING' AND NEW.status IN ('STORED', 'REJECTED')
       AND (NEW.id, NEW.document_id, NEW.version_no, NEW.storage_key, NEW.sha256, NEW.size_bytes, NEW.detected_type,
            NEW.original_name, NEW.uploaded_by, NEW.uploaded_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.document_id, OLD.version_no, OLD.storage_key, OLD.sha256, OLD.size_bytes, OLD.detected_type,
            OLD.original_name, OLD.uploaded_by, OLD.uploaded_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'document version % never changes', OLD.id USING ERRCODE = 'restrict_violation';
END;
$$;
CREATE TRIGGER document_version_immutable BEFORE UPDATE ON document_version
    FOR EACH ROW EXECUTE FUNCTION document_version_immutable();

-- Deleted only with a draft its owner deletes (the cascade from application; V13 deletes drafts only).
CREATE FUNCTION document_delete_with_draft() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM application WHERE id = OLD.application_id) THEN
        RAISE EXCEPTION 'documents are deleted only with their draft' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER document_delete_with_draft BEFORE DELETE ON document
    FOR EACH ROW EXECUTE FUNCTION document_delete_with_draft();

CREATE FUNCTION document_version_delete_with_draft() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM document d JOIN application a ON a.id = d.application_id WHERE d.id = OLD.document_id) THEN
        RAISE EXCEPTION 'documents are deleted only with their draft' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER document_version_delete_with_draft BEFORE DELETE ON document_version
    FOR EACH ROW EXECUTE FUNCTION document_version_delete_with_draft();
CREATE TRIGGER document_no_truncate BEFORE TRUNCATE ON document
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();
CREATE TRIGGER document_version_no_truncate BEFORE TRUNCATE ON document_version
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- For the cleanup of files nobody references (a crash between storing and committing, a
-- deleted draft): which of these keys a version still names. Keys only, never rows.
CREATE FUNCTION document_storage_keys_known(p_keys text[]) RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT storage_key::text FROM document_version WHERE storage_key = ANY (p_keys)
$$;

-- --- Row-level security ----------------------------------------------------------------------

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_version ENABLE ROW LEVEL SECURITY;

CREATE POLICY document_read ON document FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY document_add ON document FOR INSERT TO snigf_app
    WITH CHECK (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id AND a.applicant_id = current_actor()));
CREATE POLICY document_version_read ON document_version FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM document d WHERE d.id = document_id));
CREATE POLICY document_version_add ON document_version FOR INSERT TO snigf_app
    WITH CHECK (uploaded_by = current_actor() AND EXISTS (SELECT 1 FROM document d WHERE d.id = document_id));

-- --- Grants ----------------------------------------------------------------------------------

REVOKE ALL ON document, document_version FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION document_storage_keys_known(text[]) FROM PUBLIC;
GRANT SELECT, INSERT ON document, document_version TO snigf_app;
GRANT EXECUTE ON FUNCTION document_storage_keys_known(text[]) TO snigf_app;
