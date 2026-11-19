-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V35: the persons of a case and its ownership statement (Slice 4, ADR-043 step 5a, D35 to D40, D43). DEMO only: every
-- identity here is synthetic, entered by hand and never independently verified.
--
--   application.declaration    the applicant's, while a draft: their capacity, the persons (reference, kind, roles,
--                              DEMO identity, the applicant's phone), the declared shares and mandates. Checked at
--                              submission by case_declaration_problems(); never changed afterwards.
--   case_person, case_role     each person of the case, by the applicant's reference, and their explicit roles
--                              (APPLICANT, REPRESENTATIVE, INTENDED_OWNER; TRANSFEROR and TRANSFEREE wait for step 6).
--   case_person_identity       append-only versions: DECLARED (from the declaration) then RECORDED by an intake officer
--                              from a cited document; a correction names the version it replaces, with a reason.
--   case_ownership_statement, case_ownership_share   append-only versions of who owns what: each owner, interest and
--                              exact share, each RECORDED share citing its supporting document.
--   case_mandate, case_mandate_subject   append-only versions of a representative's mandate: whom, the scope, and the
--                              mandate document (required once RECORDED).
--   case_party_file            a document presented at the counter, scanned by the officer (ADR-033 checks; retention
--                              class by purpose: CASE_IDENTITY_DOCUMENT, CASE_MANDATE, CASE_OWNERSHIP_EVIDENCE).
--   case_party_check           one check of one recorded version, by a second officer, never its recorder: MATCHES means
--                              the information corresponds to the document presented, never that it was authenticated.
-- Every version carries UNVERIFIED_DEMO, by a CHECK that admits nothing else.
-- The gates: acceptance (COMPLETE) and an approval need every person, the ownership statement and every mandate
-- recorded and checked MATCHES, and the structure to hold; the decision names the statement it approved. A CREATION
-- from a case takes its holders from that statement, and is refused when anything changed since the decision; it is
-- signed by two conservators (D40), neither of whom handled the case.
-- Corrections of the owner's review (14 Nov 2026), at the end of this file: the rights (sole ownership, co-ownership,
-- usufruct and bare ownership with the usufruct's term; partial dismemberment refused); an identity document's validity
-- and the DEMO exception policy for an expired one; the conservators' read-only evidence; retention classes for every case
-- document and legal holds on them and on whole cases; the signed bytes of a CREATION from a case bound to its decision,
-- its statement and its holders' identities, a stale proposal refused (STALE_PROPOSAL), and the parties of every new
-- inscription signed, so a party changed behind the signatures is detected.

SET search_path = snigf, public;

-- --- Permissions ------------------------------------------------------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('application.party.record', 'BUSINESS', false, ARRAY['OFFICE'],
     'Transcribe the persons of a case from the documents presented', 'Transcrire les personnes d''un dossier depuis les pièces présentées'),
    ('application.party.check', 'BUSINESS', false, ARRAY['OFFICE'],
     'Check a transcription against the document presented (never one''s own)', 'Contrôler une transcription sur la pièce présentée (jamais la sienne)'),
    ('application.party.exception', 'BUSINESS', false, ARRAY['OFFICE'],
     'Authorise a documented exception under a DEMO policy (an expired identity document)', 'Autoriser une exception motivée selon une règle DEMO (pièce d''identité expirée)');
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('INTAKE_OFFICER', 'application.party.record', 'OFFICE'),
    ('INTAKE_OFFICER', 'application.party.check', 'OFFICE'),
    ('SUPERVISOR', 'application.party.check', 'OFFICE'),
    ('SUPERVISOR', 'application.party.exception', 'OFFICE');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- Retention classes (ADR-043, owner's review): no period, no schedule; nothing is disposed of until an approved policy
-- and its ADR exist (D18).
INSERT INTO retention_class (code, description_en, description_fr) VALUES
    ('CASE_IDENTITY_DOCUMENT', 'An identity document presented at the counter for a person of a case (ADR-043)',
     'Pièce d''identité présentée au guichet pour une personne d''un dossier (ADR-043)'),
    ('CASE_MANDATE', 'A mandate presented at the counter for a representative of a case (ADR-043)',
     'Mandat présenté au guichet pour un mandataire d''un dossier (ADR-043)'),
    ('CASE_OWNERSHIP_EVIDENCE', 'Evidence of ownership presented at the counter for a case''s ownership statement (ADR-043)',
     'Justificatif de propriété présenté au guichet pour la déclaration de propriété d''un dossier (ADR-043)'),
    ('APPLICATION_IDENTITY_DOCUMENT', 'An identity document the applicant uploaded with a case (ADR-033, ADR-043)',
     'Pièce d''identité déposée par le demandeur avec un dossier (ADR-033, ADR-043)'),
    ('APPLICATION_OWNERSHIP_EVIDENCE', 'Evidence of a right the applicant uploaded with a case (ADR-033, ADR-043)',
     'Justificatif de droit déposé par le demandeur avec un dossier (ADR-033, ADR-043)'),
    ('APPLICATION_SUPPORTING_DOCUMENT', 'Any other document the applicant uploaded with a case (ADR-033, ADR-043)',
     'Toute autre pièce déposée par le demandeur avec un dossier (ADR-033, ADR-043)');

-- --- The declaration ---------------------------------------------------------------------------------------------------

ALTER TABLE application ADD COLUMN declaration jsonb
    CHECK (declaration IS NULL OR (jsonb_typeof(declaration) = 'object' AND pg_column_size(declaration) <= 32768));
GRANT UPDATE (declaration) ON application TO snigf_app;

-- Whether a case's type needs its persons: a procedure that creates a title (procedure B's come with step 6).
CREATE FUNCTION case_parties_required(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce(application_procedure(p_application) ->> 'registers' = 'CREATION', false)
$$;

-- What is wrong with a declaration, or with the case's persons as they stand (the same shape), as reason codes:
-- {"capacity", "persons": [{"ref", "kind", "roles", "fullName", "identityKind", "identityNumber", "phone",
--  "postalAddress"}], "shares": [{"person", "interest", "shareNum", "shareDen"}], "mandates": [{"representative",
--  "represents", "scope"}]}.
CREATE FUNCTION case_declaration_problems(p jsonb) RETURNS text[]
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems   text[] := ARRAY[]::text[];
    x          jsonb;
    refs       text[] := ARRAY[]::text[];
    owners     text[] := ARRAY[]::text[];
    reps       text[] := ARRAY[]::text[];
    applicant  text;
    applicants int := 0;
    capacity   text;
    covered    text[] := ARRAY[]::text[];
    mandated   text[] := ARRAY[]::text[];
    shared     text[] := ARRAY[]::text[];
    others     int;
BEGIN
    IF p IS NULL OR jsonb_typeof(p) <> 'object' THEN
        RETURN ARRAY['DECLARATION_REQUIRED'];
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(p) k WHERE k NOT IN ('capacity', 'persons', 'shares', 'mandates')) THEN
        problems := problems || 'DECLARATION_UNKNOWN_KEY'::text;
    END IF;
    capacity := p ->> 'capacity';
    IF capacity IS NULL OR capacity NOT IN ('SELF', 'ON_BEHALF_OF_ONE', 'ON_BEHALF_OF_SEVERAL') THEN
        problems := problems || 'CAPACITY_INVALID'::text;
    END IF;
    IF jsonb_typeof(p -> 'persons') IS DISTINCT FROM 'array' OR jsonb_array_length(p -> 'persons') NOT BETWEEN 1 AND 20 THEN
        RETURN problems || 'PERSONS_REQUIRED'::text;
    END IF;

    FOR x IN SELECT value FROM jsonb_array_elements(p -> 'persons') LOOP
        IF jsonb_typeof(x) <> 'object' OR coalesce(x ->> 'ref', '') !~ '^[a-z][a-z0-9]{0,15}$' THEN
            problems := problems || 'PERSON_INVALID'::text;
            CONTINUE;
        END IF;
        IF (x ->> 'ref') = ANY (refs) THEN
            problems := problems || ('PERSON_DUPLICATE:' || (x ->> 'ref'));
            CONTINUE;
        END IF;
        refs := refs || (x ->> 'ref');
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(x) k
                    WHERE k NOT IN ('ref', 'kind', 'roles', 'fullName', 'identityKind', 'identityNumber', 'phone', 'postalAddress'))
           OR coalesce(x ->> 'kind', '') NOT IN ('NATURAL_PERSON', 'LEGAL_PERSON')
           OR length(btrim(coalesce(x ->> 'fullName', ''))) NOT BETWEEN 3 AND 200 OR (x ->> 'fullName') NOT LIKE '%DEMO%'
           OR coalesce(x ->> 'identityKind', '') !~ '^[A-Z_]{2,40}$'
           OR length(btrim(coalesce(x ->> 'identityNumber', ''))) NOT BETWEEN 5 AND 64 OR (x ->> 'identityNumber') NOT LIKE '%DEMO%'
           OR (x ->> 'phone' IS NOT NULL AND (x ->> 'phone') !~ '^\+?[0-9 ]{6,20}$')
           OR (x ->> 'postalAddress' IS NOT NULL AND length(btrim(x ->> 'postalAddress')) NOT BETWEEN 5 AND 300)
           OR jsonb_typeof(x -> 'roles') IS DISTINCT FROM 'array' OR jsonb_array_length(x -> 'roles') = 0
           OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(x -> 'roles') r WHERE r NOT IN ('APPLICANT', 'REPRESENTATIVE', 'INTENDED_OWNER'))
           OR (SELECT count(*) <> count(DISTINCT r) FROM jsonb_array_elements_text(x -> 'roles') r) THEN
            problems := problems || ('PERSON_INVALID:' || (x ->> 'ref'));
            CONTINUE;
        END IF;
        IF (x -> 'roles') ? 'APPLICANT' THEN
            applicants := applicants + 1;
            applicant := x ->> 'ref';
            IF x ->> 'phone' IS NULL THEN
                problems := problems || 'PHONE_REQUIRED'::text;
            END IF;
        END IF;
        IF (x -> 'roles') ? 'INTENDED_OWNER' THEN
            owners := owners || (x ->> 'ref');
        END IF;
        IF (x -> 'roles') ? 'REPRESENTATIVE' THEN
            reps := reps || (x ->> 'ref');
        END IF;
    END LOOP;
    IF applicants <> 1 THEN
        problems := problems || 'APPLICANT_REQUIRED'::text;
    END IF;
    IF cardinality(owners) = 0 THEN
        problems := problems || 'OWNER_REQUIRED'::text;
    END IF;
    others := cardinality(array_remove(owners, applicant));
    -- The capacity says whom the applicant applies for; the roles must say the same, explicitly.
    IF applicants = 1 AND capacity IS NOT NULL AND (
           (capacity = 'SELF' AND (NOT applicant = ANY (owners) OR others > 0 OR applicant = ANY (reps)))
        OR (capacity = 'ON_BEHALF_OF_ONE' AND (NOT applicant = ANY (reps) OR others <> 1))
        OR (capacity = 'ON_BEHALF_OF_SEVERAL' AND (NOT applicant = ANY (reps) OR others < 2))) THEN
        problems := problems || 'CAPACITY_MISMATCH'::text;
    END IF;

    -- Mandates: one per representative, of intended owners other than themselves.
    IF p -> 'mandates' IS NOT NULL AND jsonb_typeof(p -> 'mandates') <> 'array' THEN
        problems := problems || 'MANDATE_INVALID'::text;
    ELSE
        FOR x IN SELECT value FROM jsonb_array_elements(coalesce(p -> 'mandates', '[]')) LOOP
            IF jsonb_typeof(x) <> 'object'
               OR EXISTS (SELECT 1 FROM jsonb_object_keys(x) k WHERE k NOT IN ('representative', 'represents', 'scope'))
               OR NOT coalesce(x ->> 'representative', '') = ANY (reps)
               OR coalesce(x ->> 'scope', '') NOT IN ('APPLY', 'APPLY_AND_COLLECT')
               OR jsonb_typeof(x -> 'represents') IS DISTINCT FROM 'array' OR jsonb_array_length(x -> 'represents') = 0
               OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(x -> 'represents') r
                           WHERE NOT r = ANY (owners) OR r = x ->> 'representative') THEN
                problems := problems || 'MANDATE_INVALID'::text;
                CONTINUE;
            END IF;
            IF (x ->> 'representative') = ANY (mandated) THEN
                problems := problems || ('MANDATE_DUPLICATE:' || (x ->> 'representative'));
                CONTINUE;
            END IF;
            mandated := mandated || (x ->> 'representative');
            covered := covered || ARRAY(SELECT jsonb_array_elements_text(x -> 'represents'));
        END LOOP;
    END IF;
    problems := problems || ARRAY(SELECT 'REPRESENTATIVE_WITHOUT_MANDATE:' || r FROM unnest(reps) r WHERE NOT r = ANY (mandated) ORDER BY r);
    problems := problems || ARRAY(SELECT 'MANDATE_MISSING:' || o FROM unnest(owners) o
                                   WHERE o IS DISTINCT FROM applicant AND NOT o = ANY (covered) ORDER BY o);

    -- Shares: every owner has one, nobody else does, exact fractions totalling one; never an equal split by default.
    IF jsonb_typeof(p -> 'shares') IS DISTINCT FROM 'array' OR jsonb_array_length(p -> 'shares') = 0 THEN
        problems := problems || 'SHARES_REQUIRED'::text;
    ELSE
        FOR x IN SELECT value FROM jsonb_array_elements(p -> 'shares') LOOP
            IF jsonb_typeof(x) <> 'object' OR NOT coalesce(x ->> 'person', '') = ANY (owners)
               OR coalesce(x ->> 'interest', '') NOT IN ('OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP')
               OR jsonb_typeof(x -> 'shareNum') IS DISTINCT FROM 'number' OR jsonb_typeof(x -> 'shareDen') IS DISTINCT FROM 'number'
               OR (x ->> 'shareNum') !~ '^[0-9]+$' OR (x ->> 'shareDen') !~ '^[0-9]+$'
               OR (x ->> 'shareNum')::numeric < 1 OR (x ->> 'shareDen')::numeric < 1 OR (x ->> 'shareDen')::numeric > 1000000
               OR (x ->> 'shareNum')::numeric > (x ->> 'shareDen')::numeric THEN
                problems := problems || 'SHARE_INVALID'::text;
                CONTINUE;
            END IF;
            shared := shared || (x ->> 'person');
        END LOOP;
        IF NOT 'SHARE_INVALID' = ANY (problems) THEN
            IF EXISTS (SELECT 1 FROM jsonb_array_elements(p -> 'shares') s GROUP BY s ->> 'person', s ->> 'interest' HAVING count(*) > 1) THEN
                problems := problems || 'SHARE_INVALID'::text;
            ELSIF holder_rights_problem(p -> 'shares') IS NOT NULL THEN
                problems := problems || holder_rights_problem(p -> 'shares');
            END IF;
        END IF;
        problems := problems || ARRAY(SELECT 'OWNER_WITHOUT_SHARE:' || o FROM unnest(owners) o WHERE NOT o = ANY (shared) ORDER BY o);
    END IF;
    RETURN problems;
END;
$$;

-- A draft's declaration problems, for its applicant (the application shows them before submitting).
CREATE FUNCTION application_declaration_problems(p_application uuid) RETURNS text[]
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN case_parties_required(a.id) THEN case_declaration_problems(a.declaration) ELSE ARRAY[]::text[] END
      FROM application a
     WHERE a.id = p_application AND a.applicant_id = current_actor()
$$;

-- --- The persons, their identities, the ownership statement, the mandates -------------------------------------------------

CREATE TABLE case_person (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    ref             text NOT NULL CHECK (ref ~ '^[a-z][a-z0-9]{0,15}$'),
    kind            text NOT NULL CHECK (kind IN ('NATURAL_PERSON', 'LEGAL_PERSON')),
    -- The applicant's account, for the person holding the APPLICANT role only. The account is not the person.
    account_id      uuid REFERENCES app_user (id),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    demo            boolean NOT NULL DEFAULT true CHECK (demo),
    UNIQUE (application_id, ref)
);

CREATE TABLE case_role (
    person_id       uuid NOT NULL REFERENCES case_person (id),
    application_id  uuid NOT NULL REFERENCES application (id),
    role            text NOT NULL CHECK (role IN ('APPLICANT', 'REPRESENTATIVE', 'INTENDED_OWNER', 'TRANSFEROR', 'TRANSFEREE')),
    PRIMARY KEY (person_id, role)
);
CREATE UNIQUE INDEX ux_case_role_applicant ON case_role (application_id) WHERE role = 'APPLICANT';

CREATE TABLE case_party_file (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id   uuid NOT NULL REFERENCES application (id),
    purpose          text NOT NULL CHECK (purpose IN ('IDENTITY_DOCUMENT', 'MANDATE', 'OWNERSHIP_EVIDENCE')),
    storage_key      char(32) NOT NULL UNIQUE CHECK (storage_key ~ '^[0-9a-f]{32}$'),
    sha256           char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes       bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 104857600),
    detected_type    text NOT NULL CHECK (detected_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    original_name    text NOT NULL CHECK (length(original_name) BETWEEN 1 AND 200 AND original_name !~ '[/\\[:cntrl:]]'),
    uploaded_by      uuid NOT NULL REFERENCES app_user (id),
    uploaded_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    retention_class  text NOT NULL DEFAULT 'CASE_IDENTITY_DOCUMENT' REFERENCES retention_class (code)
);
CREATE INDEX ix_case_party_file_application ON case_party_file (application_id);

CREATE TABLE case_person_identity (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    person_id           uuid NOT NULL REFERENCES case_person (id),
    application_id      uuid NOT NULL REFERENCES application (id),
    version_no          int NOT NULL CHECK (version_no >= 1),
    source              text NOT NULL CHECK (source IN ('DECLARED', 'RECORDED')),
    full_name           text NOT NULL CHECK (length(btrim(full_name)) BETWEEN 3 AND 200 AND full_name LIKE '%DEMO%'),
    identity_kind       text NOT NULL CHECK (identity_kind ~ '^[A-Z_]{2,40}$'),
    identity_number     text NOT NULL CHECK (length(identity_number) BETWEEN 5 AND 64 AND identity_number LIKE '%DEMO%'),
    phone               text CHECK (phone IS NULL OR phone ~ '^\+?[0-9 ]{6,20}$'),
    postal_address      text CHECK (postal_address IS NULL OR length(btrim(postal_address)) BETWEEN 5 AND 300),
    -- The document's own expiry, when it has one; whether it had expired on the day it was transcribed. Validity, not
    -- the accuracy of the transcription: a check says nothing about it.
    valid_until         date,
    expired_at_transcription boolean NOT NULL DEFAULT false,
    source_file_id      uuid REFERENCES case_party_file (id),
    source_version_id   uuid REFERENCES document_version (id),
    entered_by          uuid NOT NULL REFERENCES app_user (id),
    entered_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    supersedes_id       uuid UNIQUE REFERENCES case_person_identity (id),
    correction_reason   text CHECK (correction_reason IS NULL OR length(btrim(correction_reason)) BETWEEN 10 AND 2000),
    -- D39: entered by hand, never independently verified; no other value can be written.
    verification        text NOT NULL DEFAULT 'UNVERIFIED_DEMO' CHECK (verification = 'UNVERIFIED_DEMO'),
    UNIQUE (person_id, version_no),
    CONSTRAINT ck_identity_source CHECK (
        (source = 'DECLARED' AND source_file_id IS NULL AND source_version_id IS NULL AND supersedes_id IS NULL)
        OR (source = 'RECORDED' AND num_nonnulls(source_file_id, source_version_id) = 1 AND supersedes_id IS NOT NULL)),
    CONSTRAINT ck_identity_validity CHECK ((source = 'RECORDED' OR valid_until IS NULL) AND (valid_until IS NOT NULL OR NOT expired_at_transcription))
);

CREATE TABLE case_ownership_statement (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id     uuid NOT NULL REFERENCES application (id),
    version_no         int NOT NULL CHECK (version_no >= 1),
    source             text NOT NULL CHECK (source IN ('DECLARED', 'RECORDED')),
    entered_by         uuid NOT NULL REFERENCES app_user (id),
    entered_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    supersedes_id      uuid UNIQUE REFERENCES case_ownership_statement (id),
    correction_reason  text CHECK (correction_reason IS NULL OR length(btrim(correction_reason)) BETWEEN 10 AND 2000),
    verification       text NOT NULL DEFAULT 'UNVERIFIED_DEMO' CHECK (verification = 'UNVERIFIED_DEMO'),
    UNIQUE (application_id, version_no),
    CONSTRAINT ck_statement_source CHECK ((source = 'DECLARED') = (supersedes_id IS NULL))
);

CREATE TABLE case_ownership_share (
    statement_id       uuid NOT NULL REFERENCES case_ownership_statement (id),
    person_id          uuid NOT NULL REFERENCES case_person (id),
    interest           text NOT NULL CHECK (interest IN ('OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP')),
    share_num          int NOT NULL CHECK (share_num >= 1),
    share_den          int NOT NULL CHECK (share_den >= 1 AND share_den <= 1000000),
    -- The usufruct's term (owner's review): for life, or until a date; nothing for any other right.
    usufruct_term      text CHECK (usufruct_term IN ('LIFE', 'UNTIL')),
    usufruct_until     date,
    basis_file_id      uuid REFERENCES case_party_file (id),
    basis_version_id   uuid REFERENCES document_version (id),
    CONSTRAINT ck_share_fraction CHECK (share_num <= share_den),
    CONSTRAINT ck_share_usufruct CHECK ((interest = 'USUFRUCT') = (usufruct_term IS NOT NULL)
                                        AND (usufruct_until IS NULL OR usufruct_term = 'UNTIL')
                                        AND (usufruct_term IS DISTINCT FROM 'UNTIL' OR usufruct_until IS NOT NULL)),
    CONSTRAINT ck_share_basis CHECK (num_nonnulls(basis_file_id, basis_version_id) <= 1),
    PRIMARY KEY (statement_id, person_id, interest)
);

CREATE TABLE case_mandate (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id     uuid NOT NULL REFERENCES application (id),
    representative_id  uuid NOT NULL REFERENCES case_person (id),
    version_no         int NOT NULL CHECK (version_no >= 1),
    source             text NOT NULL CHECK (source IN ('DECLARED', 'RECORDED')),
    scope              text NOT NULL CHECK (scope IN ('APPLY', 'APPLY_AND_COLLECT')),
    document_file_id   uuid REFERENCES case_party_file (id),
    entered_by         uuid NOT NULL REFERENCES app_user (id),
    entered_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    supersedes_id      uuid UNIQUE REFERENCES case_mandate (id),
    correction_reason  text CHECK (correction_reason IS NULL OR length(btrim(correction_reason)) BETWEEN 10 AND 2000),
    verification       text NOT NULL DEFAULT 'UNVERIFIED_DEMO' CHECK (verification = 'UNVERIFIED_DEMO'),
    UNIQUE (representative_id, version_no),
    CONSTRAINT ck_mandate_source CHECK (
        (source = 'DECLARED' AND document_file_id IS NULL AND supersedes_id IS NULL)
        OR (source = 'RECORDED' AND document_file_id IS NOT NULL AND supersedes_id IS NOT NULL))
);

CREATE TABLE case_mandate_subject (
    mandate_id  uuid NOT NULL REFERENCES case_mandate (id),
    person_id   uuid NOT NULL REFERENCES case_person (id),
    PRIMARY KEY (mandate_id, person_id)
);

CREATE TABLE case_party_check (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    target_kind     text NOT NULL CHECK (target_kind IN ('IDENTITY', 'OWNERSHIP', 'MANDATE')),
    target_id       uuid NOT NULL,
    -- MATCHES: what was entered corresponds to the document presented. Never an authentication (D39).
    outcome         text NOT NULL CHECK (outcome IN ('MATCHES', 'DOES_NOT_MATCH')),
    note            text CHECK (note IS NULL OR length(btrim(note)) BETWEEN 10 AND 1000),
    checked_by      uuid NOT NULL REFERENCES app_user (id),
    checked_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (target_kind, target_id),
    CONSTRAINT ck_check_note CHECK (outcome = 'MATCHES' OR note IS NOT NULL)
);
CREATE INDEX ix_case_party_check_application ON case_party_check (application_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['case_person', 'case_role', 'case_party_file', 'case_person_identity', 'case_ownership_statement',
                             'case_ownership_share', 'case_mandate', 'case_mandate_subject', 'case_party_check'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_modification()',
                       t || '_append_only', t);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_modification()',
                       t || '_no_truncate', t);
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION retention_class_set() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF TG_TABLE_NAME = 'case_party_file' THEN
        NEW.retention_class := CASE NEW.purpose WHEN 'IDENTITY_DOCUMENT' THEN 'CASE_IDENTITY_DOCUMENT' WHEN 'MANDATE' THEN 'CASE_MANDATE'
                                                ELSE 'CASE_OWNERSHIP_EVIDENCE' END;
        RETURN NEW;
    END IF;
    NEW.retention_class := CASE TG_TABLE_NAME
        WHEN 'professional_verification_document' THEN 'PROFESSIONAL_EVIDENCE'
        WHEN 'deposit_document' THEN 'DEPOSIT_DOCUMENT'
        WHEN 'deposit_document_replacement' THEN 'DEPOSIT_DOCUMENT_REPLACEMENT'
        WHEN 'procedure_stage_file' THEN 'PROCEDURE_STAGE_EVIDENCE'
        WHEN 'procedure_objection_file' THEN 'PROCEDURE_OBJECTION_EVIDENCE'
    END;
    RETURN NEW;
END;
$$;
CREATE TRIGGER case_party_file_retention BEFORE INSERT ON case_party_file FOR EACH ROW EXECUTE FUNCTION retention_class_set();

-- The orphan cleanup (ADR-033) keeps every file presented for a person of a case.
CREATE OR REPLACE FUNCTION document_storage_keys_known(p_keys text[]) RETURNS SETOF text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT storage_key::text FROM document_version WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM professional_verification_document WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM deposit_document_replacement WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM procedure_stage_file WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM procedure_objection_file WHERE storage_key = ANY (p_keys)
    UNION ALL
    SELECT storage_key::text FROM case_party_file WHERE storage_key = ANY (p_keys)
$$;

-- --- Submission: the declaration checked, then turned into the case's persons -------------------------------------------

CREATE FUNCTION application_declaration_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems text[];
BEGIN
    IF OLD.state <> 'DRAFT' AND NEW.declaration IS DISTINCT FROM OLD.declaration THEN
        RAISE EXCEPTION 'application % was submitted: its declaration never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED' AND case_parties_required(NEW.id) THEN
        problems := case_declaration_problems(NEW.declaration);
        IF cardinality(problems) > 0 THEN
            PERFORM procedure_refuse('DECLARATION_INVALID');
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_declaration_guard BEFORE UPDATE ON application
    FOR EACH ROW EXECUTE FUNCTION application_declaration_guard();

-- The declaration becomes DECLARED records, entered by the applicant: what they said, kept as they said it.
CREATE FUNCTION application_declaration_materialise() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    d          jsonb := NEW.declaration;
    x          jsonb;
    person     uuid;
    v_statement uuid;
    mandate    uuid;
BEGIN
    IF NOT (OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED' AND case_parties_required(NEW.id)) THEN
        RETURN NEW;
    END IF;
    FOR x IN SELECT value FROM jsonb_array_elements(d -> 'persons') LOOP
        INSERT INTO case_person (application_id, ref, kind, account_id)
        VALUES (NEW.id, x ->> 'ref', x ->> 'kind', CASE WHEN (x -> 'roles') ? 'APPLICANT' THEN NEW.applicant_id END)
        RETURNING id INTO person;
        INSERT INTO case_role (person_id, application_id, role)
        SELECT person, NEW.id, r FROM jsonb_array_elements_text(x -> 'roles') r;
        INSERT INTO case_person_identity (person_id, application_id, version_no, source, full_name, identity_kind, identity_number, phone,
                                          postal_address, entered_by)
        VALUES (person, NEW.id, 1, 'DECLARED', btrim(x ->> 'fullName'), x ->> 'identityKind', btrim(x ->> 'identityNumber'), x ->> 'phone',
                nullif(btrim(coalesce(x ->> 'postalAddress', '')), ''), NEW.applicant_id);
    END LOOP;
    INSERT INTO case_ownership_statement (application_id, version_no, source, entered_by) VALUES (NEW.id, 1, 'DECLARED', NEW.applicant_id)
    RETURNING id INTO v_statement;
    INSERT INTO case_ownership_share (statement_id, person_id, interest, share_num, share_den, usufruct_term, usufruct_until)
    SELECT v_statement, p.id, s ->> 'interest', (s ->> 'shareNum')::int, (s ->> 'shareDen')::int, s ->> 'usufructTerm', (s ->> 'usufructUntil')::date
      FROM jsonb_array_elements(d -> 'shares') s JOIN case_person p ON p.application_id = NEW.id AND p.ref = s ->> 'person';
    FOR x IN SELECT value FROM jsonb_array_elements(coalesce(d -> 'mandates', '[]')) LOOP
        INSERT INTO case_mandate (application_id, representative_id, version_no, source, scope, entered_by)
        SELECT NEW.id, p.id, 1, 'DECLARED', x ->> 'scope', NEW.applicant_id FROM case_person p
         WHERE p.application_id = NEW.id AND p.ref = x ->> 'representative'
        RETURNING id INTO mandate;
        INSERT INTO case_mandate_subject (mandate_id, person_id)
        SELECT mandate, p.id FROM jsonb_array_elements_text(x -> 'represents') r JOIN case_person p ON p.application_id = NEW.id AND p.ref = r;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_declaration_materialise AFTER UPDATE OF state ON application
    FOR EACH ROW EXECUTE FUNCTION application_declaration_materialise();

-- --- Reading the persons as they stand -------------------------------------------------------------------------------------

CREATE FUNCTION case_identity_latest(p_person uuid) RETURNS case_person_identity
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT * FROM case_person_identity WHERE person_id = p_person ORDER BY version_no DESC LIMIT 1
$$;

CREATE FUNCTION case_statement_latest(p_application uuid) RETURNS case_ownership_statement
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT * FROM case_ownership_statement WHERE application_id = p_application ORDER BY version_no DESC LIMIT 1
$$;

CREATE FUNCTION case_mandate_latest(p_representative uuid) RETURNS case_mandate
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT * FROM case_mandate WHERE representative_id = p_representative ORDER BY version_no DESC LIMIT 1
$$;

-- The case's persons, latest versions, in the declaration's shape: the structure is checked by the same rules.
CREATE FUNCTION case_parties_as_declaration(p_application uuid) RETURNS jsonb
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_strip_nulls(jsonb_build_object(
        'capacity', a.declaration ->> 'capacity',
        'persons', (SELECT coalesce(jsonb_agg(jsonb_build_object('ref', p.ref, 'kind', p.kind,
                        'roles', (SELECT jsonb_agg(r.role ORDER BY r.role) FROM case_role r WHERE r.person_id = p.id),
                        'fullName', i.full_name, 'identityKind', i.identity_kind, 'identityNumber', i.identity_number, 'phone', i.phone,
                        'postalAddress', i.postal_address) ORDER BY p.ref), '[]')
                      FROM case_person p CROSS JOIN LATERAL case_identity_latest(p.id) i WHERE p.application_id = a.id),
        'shares', (SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object('person', p.ref, 'interest', s.interest,
                        'shareNum', s.share_num, 'shareDen', s.share_den, 'usufructTerm', s.usufruct_term,
                        'usufructUntil', s.usufruct_until))), '[]')
                     FROM case_ownership_share s JOIN case_person p ON p.id = s.person_id
                    WHERE s.statement_id = (case_statement_latest(a.id)).id),
        'mandates', (SELECT coalesce(jsonb_agg(jsonb_build_object('representative', p.ref, 'scope', m.scope,
                        'represents', (SELECT jsonb_agg(q.ref ORDER BY q.ref) FROM case_mandate_subject ms JOIN case_person q ON q.id = ms.person_id
                                        WHERE ms.mandate_id = m.id))), '[]')
                       FROM case_person p CROSS JOIN LATERAL case_mandate_latest(p.id) m
                      WHERE p.application_id = a.id AND m.id IS NOT NULL)))
      FROM application a WHERE a.id = p_application
$$;

CREATE FUNCTION case_check_of(p_kind text, p_target uuid) RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT outcome FROM case_party_check WHERE target_kind = p_kind AND target_id = p_target
$$;

-- What stops the case's acceptance, approval or registration on account of its persons; empty when nothing does, or
-- when its type needs none.
CREATE FUNCTION case_party_problems(p_application uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems  text[] := ARRAY[]::text[];
    p         record;
    st        case_ownership_statement;
    status    text;
BEGIN
    IF NOT case_parties_required(p_application) THEN
        RETURN problems;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM case_person WHERE application_id = p_application) THEN
        RETURN ARRAY['PARTIES_MISSING'];
    END IF;
    problems := case_declaration_problems(case_parties_as_declaration(p_application));
    FOR p IN SELECT cp.ref, i.id, i.source, i.expired_at_transcription FROM case_person cp CROSS JOIN LATERAL case_identity_latest(cp.id) i
              WHERE cp.application_id = p_application ORDER BY cp.ref LOOP
        -- Validity is not accuracy: an expired document is a problem of its own, whatever its check says.
        IF p.expired_at_transcription AND NOT EXISTS (SELECT 1 FROM case_party_exception x WHERE x.target_kind = 'IDENTITY' AND x.target_id = p.id) THEN
            problems := problems || ('IDENTITY_DOCUMENT_EXPIRED:' || p.ref);
        END IF;
        status := case_check_of('IDENTITY', p.id);
        IF p.source = 'DECLARED' THEN
            problems := problems || ('IDENTITY_NOT_RECORDED:' || p.ref);
        ELSIF status IS NULL THEN
            problems := problems || ('IDENTITY_NOT_CHECKED:' || p.ref);
        ELSIF status <> 'MATCHES' THEN
            problems := problems || ('IDENTITY_DOES_NOT_MATCH:' || p.ref);
        END IF;
    END LOOP;
    st := case_statement_latest(p_application);
    status := case_check_of('OWNERSHIP', st.id);
    IF st.source = 'DECLARED' THEN
        problems := problems || 'OWNERSHIP_NOT_RECORDED'::text;
    ELSIF status IS NULL THEN
        problems := problems || 'OWNERSHIP_NOT_CHECKED'::text;
    ELSIF status <> 'MATCHES' THEN
        problems := problems || 'OWNERSHIP_DOES_NOT_MATCH'::text;
    END IF;
    FOR p IN SELECT cp.ref, m.id, m.source FROM case_person cp CROSS JOIN LATERAL case_mandate_latest(cp.id) m
              WHERE cp.application_id = p_application AND m.id IS NOT NULL ORDER BY cp.ref LOOP
        status := case_check_of('MANDATE', p.id);
        IF p.source = 'DECLARED' THEN
            problems := problems || ('MANDATE_NOT_RECORDED:' || p.ref);
        ELSIF status IS NULL THEN
            problems := problems || ('MANDATE_NOT_CHECKED:' || p.ref);
        ELSIF status <> 'MATCHES' THEN
            problems := problems || ('MANDATE_DOES_NOT_MATCH:' || p.ref);
        END IF;
    END LOOP;
    RETURN problems;
END;
$$;

-- --- Recording at intake, and the check -----------------------------------------------------------------------------------

-- Why the actor may not record or check persons of this case now, or NULL.
CREATE FUNCTION case_party_actor_problem(p_application uuid, p_permission text, p_actor uuid) RETURNS text
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN 'CASE_NOT_FOUND';
    END IF;
    IF NOT holds_at(p_actor, p_permission, a.office_id) THEN
        RETURN 'PARTY_FORBIDDEN';
    END IF;
    IF a.applicant_id = p_actor OR EXISTS (SELECT 1 FROM case_person cp WHERE cp.application_id = a.id AND cp.account_id = p_actor) THEN
        RETURN 'APPLICANT_CANNOT_RECORD';
    END IF;
    IF NOT case_parties_required(a.id) THEN
        RETURN 'PARTIES_NOT_APPLICABLE';
    END IF;
    IF a.state NOT IN ('COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION', 'APPROVED') THEN
        RETURN 'CASE_NOT_OPEN';
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id) THEN
        RETURN 'CASE_REGISTERED';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION case_party_file_add(p_application uuid, p_purpose text, p_storage_key text, p_sha256 text, p_size bigint, p_type text,
                                    p_name text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor   uuid := current_actor();
    problem text;
    new_id  uuid;
BEGIN
    PERFORM 1 FROM application WHERE id = p_application FOR UPDATE;
    problem := case_party_actor_problem(p_application, 'application.party.record', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF p_purpose IS NULL OR p_purpose NOT IN ('IDENTITY_DOCUMENT', 'MANDATE', 'OWNERSHIP_EVIDENCE') THEN
        PERFORM procedure_refuse('PURPOSE_INVALID');
    END IF;
    INSERT INTO case_party_file (application_id, purpose, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
    VALUES (p_application, p_purpose, p_storage_key, p_sha256, p_size, p_type, p_name, actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- A cited source: a counter file of this case for this purpose, or a stored version of the case's own documents.
CREATE FUNCTION case_source_valid(p_application uuid, p_purpose text, p_file uuid, p_version uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT num_nonnulls(p_file, p_version) = 1
       AND (p_file IS NULL OR EXISTS (SELECT 1 FROM case_party_file f WHERE f.id = p_file AND f.application_id = p_application
                                                                          AND f.purpose = p_purpose))
       AND (p_version IS NULL OR EXISTS (SELECT 1 FROM document_version v JOIN document d ON d.id = v.document_id
                                          WHERE v.id = p_version AND d.application_id = p_application AND v.status = 'STORED'))
$$;

-- p_identity: {"fullName", "identityKind", "identityNumber", "phone", "postalAddress"}, transcribed from the source.
CREATE FUNCTION case_identity_record(p_person uuid, p_identity jsonb, p_source_file uuid, p_source_version uuid, p_supersedes uuid,
                                     p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    cp       case_person;
    latest   case_person_identity;
    problem  text;
    new_id   uuid;
BEGIN
    SELECT * INTO cp FROM case_person WHERE id = p_person;
    IF cp.id IS NULL THEN
        PERFORM procedure_refuse('PERSON_NOT_FOUND');
    END IF;
    PERFORM 1 FROM application WHERE id = cp.application_id FOR UPDATE;
    problem := case_party_actor_problem(cp.application_id, 'application.party.record', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    latest := case_identity_latest(cp.id);
    IF p_supersedes IS DISTINCT FROM latest.id THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF latest.source = 'RECORDED' AND (p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000) THEN
        PERFORM procedure_refuse('CORRECTION_REASON_REQUIRED');
    END IF;
    IF NOT case_source_valid(cp.application_id, 'IDENTITY_DOCUMENT', p_source_file, p_source_version) THEN
        PERFORM procedure_refuse('SOURCE_REQUIRED');
    END IF;
    IF p_identity IS NULL OR jsonb_typeof(p_identity) <> 'object'
       OR EXISTS (SELECT 1 FROM jsonb_object_keys(p_identity) k WHERE k NOT IN ('fullName', 'identityKind', 'identityNumber', 'phone', 'postalAddress',
                                                                             'validUntil'))
       OR (p_identity ->> 'validUntil' IS NOT NULL AND NOT pg_input_is_valid(p_identity ->> 'validUntil', 'date'))
       OR length(btrim(coalesce(p_identity ->> 'fullName', ''))) NOT BETWEEN 3 AND 200 OR (p_identity ->> 'fullName') NOT LIKE '%DEMO%'
       OR coalesce(p_identity ->> 'identityKind', '') !~ '^[A-Z_]{2,40}$'
       OR length(btrim(coalesce(p_identity ->> 'identityNumber', ''))) NOT BETWEEN 5 AND 64 OR (p_identity ->> 'identityNumber') NOT LIKE '%DEMO%'
       OR (p_identity ->> 'phone' IS NOT NULL AND (p_identity ->> 'phone') !~ '^\+?[0-9 ]{6,20}$')
       OR (p_identity ->> 'postalAddress' IS NOT NULL AND length(btrim(p_identity ->> 'postalAddress')) NOT BETWEEN 5 AND 300) THEN
        PERFORM procedure_refuse('IDENTITY_INVALID');
    END IF;
    IF EXISTS (SELECT 1 FROM case_role r WHERE r.person_id = cp.id AND r.role = 'APPLICANT') AND p_identity ->> 'phone' IS NULL THEN
        PERFORM procedure_refuse('PHONE_REQUIRED');
    END IF;
    INSERT INTO case_person_identity (person_id, application_id, version_no, source, full_name, identity_kind, identity_number, phone,
                                      postal_address, valid_until, expired_at_transcription, source_file_id, source_version_id, entered_by,
                                      supersedes_id, correction_reason)
    VALUES (cp.id, cp.application_id, latest.version_no + 1, 'RECORDED', btrim(p_identity ->> 'fullName'), p_identity ->> 'identityKind',
            btrim(p_identity ->> 'identityNumber'), p_identity ->> 'phone', nullif(btrim(coalesce(p_identity ->> 'postalAddress', '')), ''),
            (p_identity ->> 'validUntil')::date, coalesce((p_identity ->> 'validUntil')::date < current_date, false),
            p_source_file, p_source_version, actor, latest.id, nullif(btrim(coalesce(p_reason, '')), ''))
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- p_shares: [{"person" (a case_person id), "interest", "shareNum", "shareDen", "basisFile" | "basisVersion"}], each share
-- citing the legal document it comes from. Every intended owner has a share; together they total one.
CREATE FUNCTION case_ownership_record(p_application uuid, p_shares jsonb, p_supersedes uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    latest   case_ownership_statement;
    problem  text;
    new_id   uuid;
    sh       jsonb;
BEGIN
    PERFORM 1 FROM application WHERE id = p_application FOR UPDATE;
    problem := case_party_actor_problem(p_application, 'application.party.record', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    latest := case_statement_latest(p_application);
    IF p_supersedes IS DISTINCT FROM latest.id THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF latest.source = 'RECORDED' AND (p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000) THEN
        PERFORM procedure_refuse('CORRECTION_REASON_REQUIRED');
    END IF;
    IF p_shares IS NULL OR jsonb_typeof(p_shares) <> 'array' OR jsonb_array_length(p_shares) = 0 THEN
        PERFORM procedure_refuse('SHARES_REQUIRED');
    END IF;
    FOR sh IN SELECT value FROM jsonb_array_elements(p_shares) LOOP
        IF jsonb_typeof(sh) <> 'object'
           OR EXISTS (SELECT 1 FROM jsonb_object_keys(sh) k WHERE k NOT IN ('person', 'interest', 'shareNum', 'shareDen', 'basisFile', 'basisVersion',
                                                                         'usufructTerm', 'usufructUntil'))
           OR coalesce(sh ->> 'person', '') !~ '^[0-9a-f-]{36}$'
           OR NOT EXISTS (SELECT 1 FROM case_role r WHERE r.person_id = (sh ->> 'person')::uuid AND r.application_id = p_application
                                                      AND r.role = 'INTENDED_OWNER')
           OR coalesce(sh ->> 'interest', '') NOT IN ('OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP')
           OR coalesce(sh ->> 'shareNum', '') !~ '^[0-9]{1,7}$' OR coalesce(sh ->> 'shareDen', '') !~ '^[0-9]{1,7}$'
           OR (sh ->> 'shareNum')::int < 1 OR (sh ->> 'shareDen')::int < 1 OR (sh ->> 'shareDen')::int > 1000000
           OR (sh ->> 'shareNum')::int > (sh ->> 'shareDen')::int THEN
            PERFORM procedure_refuse('SHARE_INVALID');
        END IF;
        IF (sh ->> 'basisFile' IS NOT NULL AND (sh ->> 'basisFile') !~ '^[0-9a-f-]{36}$')
           OR (sh ->> 'basisVersion' IS NOT NULL AND (sh ->> 'basisVersion') !~ '^[0-9a-f-]{36}$')
           OR NOT case_source_valid(p_application, 'OWNERSHIP_EVIDENCE', (sh ->> 'basisFile')::uuid, (sh ->> 'basisVersion')::uuid) THEN
            PERFORM procedure_refuse('BASIS_REQUIRED');
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_shares) s GROUP BY s ->> 'person', s ->> 'interest' HAVING count(*) > 1) THEN
        PERFORM procedure_refuse('SHARE_INVALID');
    END IF;
    IF holder_rights_problem(p_shares) IS NOT NULL THEN
        PERFORM procedure_refuse(holder_rights_problem(p_shares));
    END IF;
    -- A term that ended before the statement is recorded is no usufruct.
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_shares) x WHERE (x ->> 'usufructUntil')::date <= current_date) THEN
        PERFORM procedure_refuse('USUFRUCT_TERM_INVALID');
    END IF;
    IF EXISTS (SELECT 1 FROM case_role r WHERE r.application_id = p_application AND r.role = 'INTENDED_OWNER'
                AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p_shares) s WHERE (s ->> 'person')::uuid = r.person_id)) THEN
        PERFORM procedure_refuse('OWNER_WITHOUT_SHARE');
    END IF;
    INSERT INTO case_ownership_statement (application_id, version_no, source, entered_by, supersedes_id, correction_reason)
    VALUES (p_application, latest.version_no + 1, 'RECORDED', actor, latest.id, nullif(btrim(coalesce(p_reason, '')), ''))
    RETURNING id INTO new_id;
    INSERT INTO case_ownership_share (statement_id, person_id, interest, share_num, share_den, usufruct_term, usufruct_until,
                                      basis_file_id, basis_version_id)
    SELECT new_id, (s ->> 'person')::uuid, s ->> 'interest', (s ->> 'shareNum')::int, (s ->> 'shareDen')::int, s ->> 'usufructTerm',
           (s ->> 'usufructUntil')::date, (s ->> 'basisFile')::uuid, (s ->> 'basisVersion')::uuid
      FROM jsonb_array_elements(p_shares) s;
    RETURN new_id;
END;
$$;

-- A representative's mandate as the document says: whom (intended owners other than themselves), the scope, the
-- mandate document scanned at the counter (required).
CREATE FUNCTION case_mandate_record(p_representative uuid, p_represents uuid[], p_scope text, p_document_file uuid, p_supersedes uuid,
                                    p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    cp       case_person;
    latest   case_mandate;
    problem  text;
    new_id   uuid;
BEGIN
    SELECT * INTO cp FROM case_person WHERE id = p_representative;
    IF cp.id IS NULL THEN
        PERFORM procedure_refuse('PERSON_NOT_FOUND');
    END IF;
    PERFORM 1 FROM application WHERE id = cp.application_id FOR UPDATE;
    problem := case_party_actor_problem(cp.application_id, 'application.party.record', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM case_role r WHERE r.person_id = cp.id AND r.role = 'REPRESENTATIVE') THEN
        PERFORM procedure_refuse('NOT_A_REPRESENTATIVE');
    END IF;
    latest := case_mandate_latest(cp.id);
    IF p_supersedes IS DISTINCT FROM latest.id OR latest.id IS NULL THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF latest.source = 'RECORDED' AND (p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000) THEN
        PERFORM procedure_refuse('CORRECTION_REASON_REQUIRED');
    END IF;
    IF p_scope IS NULL OR p_scope NOT IN ('APPLY', 'APPLY_AND_COLLECT') OR p_represents IS NULL OR cardinality(p_represents) = 0
       OR p_representative = ANY (p_represents)
       OR EXISTS (SELECT 1 FROM unnest(p_represents) r WHERE NOT EXISTS (SELECT 1 FROM case_role x WHERE x.person_id = r
                                                                          AND x.application_id = cp.application_id AND x.role = 'INTENDED_OWNER')) THEN
        PERFORM procedure_refuse('MANDATE_INVALID');
    END IF;
    IF NOT case_source_valid(cp.application_id, 'MANDATE', p_document_file, NULL) THEN
        PERFORM procedure_refuse('MANDATE_DOCUMENT_REQUIRED');
    END IF;
    INSERT INTO case_mandate (application_id, representative_id, version_no, source, scope, document_file_id, entered_by, supersedes_id,
                              correction_reason)
    VALUES (cp.application_id, cp.id, latest.version_no + 1, 'RECORDED', p_scope, p_document_file, actor, latest.id,
            nullif(btrim(coalesce(p_reason, '')), ''))
    RETURNING id INTO new_id;
    INSERT INTO case_mandate_subject (mandate_id, person_id) SELECT new_id, r FROM (SELECT DISTINCT unnest(p_represents) AS r) x;
    RETURN new_id;
END;
$$;

-- One check of one recorded version, its latest, by someone who did not record it (D38). MATCHES: what was entered
-- corresponds to the document presented, nothing more (D39).
CREATE FUNCTION case_party_check_record(p_kind text, p_target uuid, p_outcome text, p_note text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    app       uuid;
    source    text;
    recorder  uuid;
    is_latest boolean;
    problem   text;
    new_id    uuid;
BEGIN
    IF p_kind = 'IDENTITY' THEN
        SELECT i.application_id, i.source, i.entered_by, i.id = (case_identity_latest(i.person_id)).id
          INTO app, source, recorder, is_latest FROM case_person_identity i WHERE i.id = p_target;
    ELSIF p_kind = 'OWNERSHIP' THEN
        SELECT s.application_id, s.source, s.entered_by, s.id = (case_statement_latest(s.application_id)).id
          INTO app, source, recorder, is_latest FROM case_ownership_statement s WHERE s.id = p_target;
    ELSIF p_kind = 'MANDATE' THEN
        SELECT m.application_id, m.source, m.entered_by, m.id = (case_mandate_latest(m.representative_id)).id
          INTO app, source, recorder, is_latest FROM case_mandate m WHERE m.id = p_target;
    ELSE
        PERFORM procedure_refuse('CHECK_TARGET_INVALID');
    END IF;
    IF app IS NULL THEN
        PERFORM procedure_refuse('CHECK_TARGET_INVALID');
    END IF;
    PERFORM 1 FROM application WHERE id = app FOR UPDATE;
    problem := case_party_actor_problem(app, 'application.party.check', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF source <> 'RECORDED' THEN
        PERFORM procedure_refuse('NOT_YET_RECORDED');
    END IF;
    IF NOT is_latest THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF recorder = actor THEN
        PERFORM procedure_refuse('RECORDER_CANNOT_CHECK');
    END IF;
    IF EXISTS (SELECT 1 FROM case_party_check c WHERE c.target_kind = p_kind AND c.target_id = p_target) THEN
        PERFORM procedure_refuse('ALREADY_CHECKED');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('MATCHES', 'DOES_NOT_MATCH') THEN
        PERFORM procedure_refuse('OUTCOME_REQUIRED');
    END IF;
    IF p_outcome = 'DOES_NOT_MATCH' AND (p_note IS NULL OR length(btrim(p_note)) NOT BETWEEN 10 AND 1000) THEN
        PERFORM procedure_refuse('CHECK_NOTE_REQUIRED');
    END IF;
    IF p_note IS NOT NULL AND length(btrim(p_note)) NOT BETWEEN 10 AND 1000 THEN
        PERFORM procedure_refuse('CHECK_NOTE_REQUIRED');
    END IF;
    INSERT INTO case_party_check (application_id, target_kind, target_id, outcome, note, checked_by)
    VALUES (app, p_kind, p_target, p_outcome, nullif(btrim(coalesce(p_note, '')), ''), actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- --- The gates: acceptance, approval ------------------------------------------------------------------------------------

CREATE FUNCTION completeness_parties_gate() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.kind = 'COMPLETE' AND cardinality(case_party_problems(NEW.application_id)) > 0 THEN
        PERFORM procedure_refuse('PARTIES_INCOMPLETE');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER completeness_parties_gate BEFORE INSERT ON completeness_record
    FOR EACH ROW EXECUTE FUNCTION completeness_parties_gate();

-- The decision names the ownership statement it approved; an approval needs the persons to hold.
ALTER TABLE decision ADD COLUMN ownership_statement_id uuid REFERENCES case_ownership_statement (id);

CREATE FUNCTION decision_parties_gate() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    NEW.ownership_statement_id := NULL;
    IF case_parties_required(NEW.application_id) THEN
        IF NEW.outcome = 'APPROVED' AND cardinality(case_party_problems(NEW.application_id)) > 0 THEN
            PERFORM procedure_refuse('PARTIES_INCOMPLETE');
        END IF;
        NEW.ownership_statement_id := (case_statement_latest(NEW.application_id)).id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER decision_parties_gate BEFORE INSERT ON decision FOR EACH ROW EXECUTE FUNCTION decision_parties_gate();


-- --- The register: holders from the decided statement, two signatures ------------------------------------------------------

ALTER TABLE register_party ADD COLUMN source_identity_id uuid REFERENCES case_person_identity (id);
ALTER TABLE register_party ADD COLUMN source_identity_version int CHECK (source_identity_version >= 1);
ALTER TABLE register_party ADD CONSTRAINT ck_party_source CHECK ((source_identity_id IS NULL) = (source_identity_version IS NULL));
-- The rights of a holder (owner's review): the usufruct's term, as on a case's share.
ALTER TABLE inscription_holder ADD COLUMN usufruct_term text CHECK (usufruct_term IN ('LIFE', 'UNTIL'));
ALTER TABLE inscription_holder ADD COLUMN usufruct_until date;
ALTER TABLE inscription_holder ADD CONSTRAINT ck_holder_usufruct CHECK ((interest = 'USUFRUCT') = (usufruct_term IS NOT NULL)
    AND (usufruct_until IS NULL OR usufruct_term = 'UNTIL') AND (usufruct_term IS DISTINCT FROM 'UNTIL' OR usufruct_until IS NOT NULL));
-- What a CREATION from a case is bound to (owner's review): the decision and the statement it was proposed under. And
-- whether its signed bytes carry its parties' identities: every inscription proposed from V35 on; earlier ones keep their
-- bytes, so their signatures still verify.
ALTER TABLE inscription ADD COLUMN decision_id uuid REFERENCES decision (id);
ALTER TABLE inscription ADD COLUMN ownership_statement_id uuid REFERENCES case_ownership_statement (id);
ALTER TABLE inscription ADD COLUMN ownership_statement_version int;
ALTER TABLE inscription ADD COLUMN binds_parties boolean NOT NULL DEFAULT false;
ALTER TABLE inscription ALTER COLUMN binds_parties SET DEFAULT true;
ALTER TABLE inscription ADD CONSTRAINT ck_inscription_case_binding CHECK (
    (application_id IS NULL AND decision_id IS NULL AND ownership_statement_id IS NULL AND ownership_statement_version IS NULL)
    OR (application_id IS NOT NULL AND decision_id IS NOT NULL AND ownership_statement_id IS NOT NULL AND ownership_statement_version IS NOT NULL));

-- --- Corrections of the owner's review (14 Nov 2026): the rights, a document's validity, the binding ---------------------

-- The rights of a set of holders, as reason codes (NULL when they hold). Sole ownership: one holder, 1/1. Co-ownership:
-- two holders or more, each below one, together one. Usufruct and bare ownership: both present, each totalling one, every
-- usufruct with its term (LIFE, or UNTIL with a date). Anything else, partial dismemberment included, is refused. Used by
-- the declaration, the ownership statement and every register proposal alike.
CREATE FUNCTION holder_rights_problem(p_holders jsonb) RETURNS text
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    kinds  text[];
    h      record;
    grp    text;
    num    numeric;
    den    numeric;
    n      int;
BEGIN
    IF p_holders IS NULL OR jsonb_typeof(p_holders) <> 'array' OR jsonb_array_length(p_holders) = 0 THEN
        RETURN 'HOLDERS_REQUIRED';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_holders) x
                WHERE jsonb_typeof(x) <> 'object' OR coalesce(x ->> 'interest', '') NOT IN ('OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP')
                   OR coalesce(x ->> 'shareNum', '') !~ '^[0-9]{1,7}$' OR coalesce(x ->> 'shareDen', '') !~ '^[0-9]{1,7}$'
                   OR (x ->> 'shareNum')::int < 1 OR (x ->> 'shareDen')::int < 1 OR (x ->> 'shareNum')::int > (x ->> 'shareDen')::int) THEN
        RETURN 'SHARE_INVALID';
    END IF;
    -- The usufruct's term: on a usufruct only, and always there.
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_holders) x
                WHERE (x ->> 'interest' = 'USUFRUCT') <> (x ->> 'usufructTerm' IS NOT NULL)
                   OR (x ->> 'usufructTerm' IS NOT NULL AND x ->> 'usufructTerm' NOT IN ('LIFE', 'UNTIL'))
                   OR ((x ->> 'usufructTerm' = 'UNTIL') <> (x ->> 'usufructUntil' IS NOT NULL) AND x ->> 'usufructTerm' IS NOT NULL)
                   OR (x ->> 'usufructTerm' IS NULL AND x ->> 'usufructUntil' IS NOT NULL)
                   OR (x ->> 'usufructUntil' IS NOT NULL AND NOT pg_input_is_valid(x ->> 'usufructUntil', 'date'))) THEN
        RETURN 'USUFRUCT_TERM_INVALID';
    END IF;
    SELECT array_agg(DISTINCT x ->> 'interest' ORDER BY x ->> 'interest') INTO kinds FROM jsonb_array_elements(p_holders) x;
    IF kinds = ARRAY['OWNERSHIP'] THEN
        n := jsonb_array_length(p_holders);
        IF n <> 1 OR EXISTS (SELECT 1 FROM jsonb_array_elements(p_holders) x WHERE (x ->> 'shareNum')::int <> (x ->> 'shareDen')::int) THEN
            RETURN 'SOLE_OWNERSHIP_INVALID';
        END IF;
        RETURN NULL;
    END IF;
    IF kinds = ARRAY['CO_OWNERSHIP'] THEN
        IF jsonb_array_length(p_holders) < 2
           OR EXISTS (SELECT 1 FROM jsonb_array_elements(p_holders) x WHERE (x ->> 'shareNum')::int = (x ->> 'shareDen')::int) THEN
            RETURN 'CO_OWNERSHIP_INVALID';
        END IF;
    ELSIF kinds = ARRAY['BARE_OWNERSHIP', 'USUFRUCT'] THEN
        NULL;
    ELSIF 'USUFRUCT' = ANY (kinds) OR 'BARE_OWNERSHIP' = ANY (kinds) THEN
        RETURN 'PARTIAL_DISMEMBERMENT_NOT_SUPPORTED';
    ELSE
        RETURN 'RIGHTS_INCONSISTENT';
    END IF;
    -- Each group totals exactly one, in exact fractions.
    FOREACH grp IN ARRAY kinds LOOP
        num := 0;
        den := 1;
        FOR h IN SELECT (x ->> 'shareNum')::numeric AS a, (x ->> 'shareDen')::numeric AS b FROM jsonb_array_elements(p_holders) x
                  WHERE x ->> 'interest' = grp LOOP
            num := num * h.b + h.a * den;
            den := den * h.b;
        END LOOP;
        IF num <> den THEN
            RETURN 'SHARES_MUST_TOTAL_ONE';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

-- The register's own check, now the same rules (V21's name kept for its callers).
CREATE OR REPLACE FUNCTION holder_shares_total_one(p_holders jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT holder_rights_problem(p_holders) IS NULL
$$;

-- A documented exception under a defined DEMO policy (owner's review): an identity document that had expired on the day
-- it was transcribed is not acceptable evidence unless replaced by a valid one, or accepted by a supervisor who did not
-- transcribe it, with a written reason. DEMO only: no legal rule is implied (to be confirmed with MINDCAF).
CREATE TABLE case_party_exception (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id  uuid NOT NULL REFERENCES application (id),
    target_kind     text NOT NULL CHECK (target_kind = 'IDENTITY'),
    target_id       uuid NOT NULL REFERENCES case_person_identity (id),
    policy          text NOT NULL CHECK (policy = 'DEMO_EXPIRED_IDENTITY_DOCUMENT'),
    reason          text NOT NULL CHECK (length(btrim(reason)) BETWEEN 20 AND 2000),
    authorised_by   uuid NOT NULL REFERENCES app_user (id),
    authorised_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (target_kind, target_id, policy)
);
CREATE TRIGGER case_party_exception_append_only BEFORE UPDATE OR DELETE ON case_party_exception FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER case_party_exception_no_truncate BEFORE TRUNCATE ON case_party_exception FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE FUNCTION case_party_exception_record(p_identity uuid, p_policy text, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    i        case_person_identity;
    problem  text;
    new_id   uuid;
BEGIN
    SELECT * INTO i FROM case_person_identity WHERE id = p_identity;
    IF i.id IS NULL THEN
        PERFORM procedure_refuse('CHECK_TARGET_INVALID');
    END IF;
    PERFORM 1 FROM application WHERE id = i.application_id FOR UPDATE;
    problem := case_party_actor_problem(i.application_id, 'application.party.exception', actor);
    IF problem IS NOT NULL THEN
        PERFORM procedure_refuse(problem);
    END IF;
    IF i.id IS DISTINCT FROM (case_identity_latest(i.person_id)).id THEN
        PERFORM procedure_refuse('STALE_VERSION');
    END IF;
    IF NOT i.expired_at_transcription THEN
        PERFORM procedure_refuse('EXCEPTION_NOT_APPLICABLE');
    END IF;
    IF p_policy IS DISTINCT FROM 'DEMO_EXPIRED_IDENTITY_DOCUMENT' THEN
        PERFORM procedure_refuse('POLICY_INVALID');
    END IF;
    IF i.entered_by = actor THEN
        PERFORM procedure_refuse('RECORDER_CANNOT_AUTHORISE');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 20 AND 2000 THEN
        PERFORM procedure_refuse('REASON_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM case_party_exception x WHERE x.target_kind = 'IDENTITY' AND x.target_id = i.id) THEN
        PERFORM procedure_refuse('ALREADY_AUTHORISED');
    END IF;
    INSERT INTO case_party_exception (application_id, target_kind, target_id, policy, reason, authorised_by)
    VALUES (i.application_id, 'IDENTITY', i.id, p_policy, btrim(p_reason), actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- Whether a CREATION from a case no longer is what was approved: another decision, another statement, the parcel at
-- another version, a holder whose identity has a newer version, or one no longer checked MATCHES. Such a proposal is never signed (STALE_PROPOSAL).
CREATE FUNCTION register_case_proposal_stale(p_inscription uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM inscription i
         WHERE i.id = p_inscription AND i.application_id IS NOT NULL
           AND (i.decision_id IS DISTINCT FROM (SELECT d.id FROM decision d WHERE d.application_id = i.application_id)
                OR i.ownership_statement_id IS DISTINCT FROM (SELECT d.ownership_statement_id FROM decision d WHERE d.application_id = i.application_id)
                OR i.ownership_statement_id IS DISTINCT FROM (case_statement_latest(i.application_id)).id
                -- The parcel as signed is still the case's parcel at its current version.
                OR i.parcel_version_id IS DISTINCT FROM (SELECT v.id FROM application a JOIN parcel p ON p.id = a.parcel_id
                                                          JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                                                         WHERE a.id = i.application_id)
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                             JOIN case_person_identity src ON src.id = rp.source_identity_id
                            WHERE h.inscription_id = i.id
                              AND (src.id IS DISTINCT FROM (case_identity_latest(src.person_id)).id
                                   OR case_check_of('IDENTITY', src.id) IS DISTINCT FROM 'MATCHES'))
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                            WHERE h.inscription_id = i.id AND rp.source_identity_id IS NULL)))
$$;

-- The case binding of an inscription never changes (V34's guard, now with the decision, the statement and the parties' binding).
CREATE OR REPLACE FUNCTION inscription_case_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.application_id, NEW.decision_id, NEW.ownership_statement_id, NEW.ownership_statement_version, NEW.binds_parties)
       IS DISTINCT FROM (OLD.application_id, OLD.decision_id, OLD.ownership_statement_id, OLD.ownership_statement_version, OLD.binds_parties) THEN
        RAISE EXCEPTION 'the case of an inscription never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- The signed bytes (owner's review). Appended to V34's, each part only when it applies, so the bytes of every earlier
-- inscription are unchanged: the decision and the statement a CREATION from a case was proposed under; the usufruct's
-- term of each holder that has one; and, for every inscription proposed from V35 on, each holder's and the beneficiary's
-- identity as the register records it, and for a case's owner the identity version it came from. A party changed behind
-- the signatures no longer matches them, and the title's verification says so.
CREATE OR REPLACE FUNCTION inscription_canonical(p_inscription uuid) RETURNS bytea
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    encoded bytea := inscription_canonical_fields(p_inscription);
    i       inscription;
    n       bigint;
    d       record;
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription;
    SELECT count(*) INTO n FROM deposit_document x WHERE x.transaction_id = i.completes_transaction_id;
    IF n > 0 THEN
        encoded := encoded || canonical_text(n::text);
        FOR d IN SELECT x.id, x.sha256 FROM deposit_document x WHERE x.transaction_id = i.completes_transaction_id ORDER BY x.id LOOP
            encoded := encoded || canonical_text(d.id::text) || canonical_text(d.sha256::text);
        END LOOP;
    END IF;
    IF i.application_id IS NOT NULL THEN
        encoded := encoded || canonical_text('case') || canonical_text(i.application_id::text);
    END IF;
    IF i.decision_id IS NOT NULL THEN
        encoded := encoded || canonical_text('decision') || canonical_text(i.decision_id::text)
                   || canonical_text('statement') || canonical_text(i.ownership_statement_id::text)
                   || canonical_text(i.ownership_statement_version::text);
    END IF;
    SELECT count(*) INTO n FROM inscription_holder h WHERE h.inscription_id = i.id AND h.usufruct_term IS NOT NULL;
    IF n > 0 THEN
        encoded := encoded || canonical_text('usufruct') || canonical_text(n::text);
        FOR d IN SELECT h.party_id, h.interest, h.usufruct_term, h.usufruct_until FROM inscription_holder h
                  WHERE h.inscription_id = i.id AND h.usufruct_term IS NOT NULL ORDER BY h.party_id, h.interest LOOP
            encoded := encoded || canonical_text(d.party_id::text) || canonical_text(d.interest) || canonical_text(d.usufruct_term)
                       || canonical_text(d.usufruct_until::text);
        END LOOP;
    END IF;
    IF i.binds_parties THEN
        SELECT count(*) INTO n FROM inscription_holder h WHERE h.inscription_id = i.id;
        encoded := encoded || canonical_text('parties') || canonical_text(n::text);
        FOR d IN SELECT h.party_id, h.interest, p.kind, p.full_name, p.identity_kind, p.identity_number, p.source_identity_id, p.source_identity_version
                   FROM inscription_holder h JOIN register_party p ON p.id = h.party_id
                  WHERE h.inscription_id = i.id ORDER BY h.party_id, h.interest LOOP
            encoded := encoded || canonical_text(d.party_id::text) || canonical_text(d.interest) || canonical_text(d.kind)
                       || canonical_text(d.full_name) || canonical_text(d.identity_kind) || canonical_text(d.identity_number)
                       || canonical_text(d.source_identity_id::text) || canonical_text(d.source_identity_version::text);
        END LOOP;
        SELECT p.id, p.kind, p.full_name, p.identity_kind, p.identity_number INTO d
          FROM inscription_charge c JOIN register_party p ON p.id = c.beneficiary_id WHERE c.inscription_id = i.id;
        encoded := encoded || canonical_text('beneficiary') || canonical_text(d.id::text) || canonical_text(d.kind) || canonical_text(d.full_name)
                   || canonical_text(d.identity_kind) || canonical_text(d.identity_number);
    END IF;
    RETURN encoded;
END;
$$;

-- A title's derived state keeps a usufruct's term (only when there is one, so every earlier version recomputes as written).
CREATE OR REPLACE FUNCTION title_state(p_title uuid, p_version int) RETURNS jsonb
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    status_now   text := 'ACTIVE';
    area_now     numeric(14, 2);
    parcel_now   uuid;
    holders_now  jsonb := '[]';
    charges_now  jsonb := '[]';
    restrict_now jsonb := '[]';
    e            inscription;
    content      inscription;
    entry        jsonb;
BEGIN
    FOR e IN
        SELECT * FROM inscription x
         WHERE x.title_id = p_title AND x.status = 'REGISTERED' AND x.number <= p_version
           AND x.kind NOT IN ('CANCELLATION', 'CORRECTION')
           AND NOT EXISTS (SELECT 1 FROM inscription c WHERE c.kind = 'CANCELLATION' AND c.status = 'REGISTERED'
                            AND c.target_inscription_id = x.id AND c.number <= p_version)
         ORDER BY x.number
    LOOP
        SELECT * INTO content FROM inscription c
         WHERE c.kind = 'CORRECTION' AND c.status = 'REGISTERED' AND c.target_inscription_id = e.id AND c.number <= p_version
         ORDER BY c.number DESC LIMIT 1;
        IF content.id IS NULL THEN
            content := e;
        END IF;
        CASE e.kind
            WHEN 'CREATION', 'TRANSFER' THEN
                IF e.kind = 'CREATION' THEN
                    area_now := coalesce(content.area_m2, e.area_m2);
                    parcel_now := coalesce(content.parcel_version_id, e.parcel_version_id);
                END IF;
                holders_now := (SELECT coalesce(jsonb_agg(jsonb_build_object('party', h.party_id, 'interest', h.interest,
                                                                             'shareNum', h.share_num, 'shareDen', h.share_den)
                                                          || jsonb_strip_nulls(jsonb_build_object('usufructTerm', h.usufruct_term,
                                                                                                  'usufructUntil', h.usufruct_until))
                                                          ORDER BY h.party_id, h.interest), '[]')
                                  FROM inscription_holder h WHERE h.inscription_id = content.id);
            WHEN 'CHARGE', 'RESTRICTION' THEN
                entry := (SELECT jsonb_build_object('inscription', e.id, 'number', e.number, 'kind', ch.charge_kind,
                                                    'beneficiary', ch.beneficiary_id, 'amount', ch.amount,
                                                    'blocksTransfer', ch.blocks_transfer, 'endsOn', ch.ends_on, 'note', ch.note)
                            FROM inscription_charge ch WHERE ch.inscription_id = content.id);
                IF e.kind = 'CHARGE' THEN
                    charges_now := charges_now || jsonb_build_array(entry);
                ELSE
                    restrict_now := restrict_now || jsonb_build_array(entry);
                END IF;
            WHEN 'CHARGE_RELEASE' THEN
                charges_now := (SELECT coalesce(jsonb_agg(x ORDER BY ord), '[]') FROM jsonb_array_elements(charges_now) WITH ORDINALITY a(x, ord)
                                 WHERE x->>'inscription' <> e.target_inscription_id::text);
            WHEN 'RESTRICTION_RELEASE' THEN
                restrict_now := (SELECT coalesce(jsonb_agg(x ORDER BY ord), '[]') FROM jsonb_array_elements(restrict_now) WITH ORDINALITY a(x, ord)
                                  WHERE x->>'inscription' <> e.target_inscription_id::text);
            WHEN 'CLOSURE' THEN
                status_now := 'CLOSED';
            ELSE
                NULL;
        END CASE;
    END LOOP;
    RETURN jsonb_build_object('status', status_now, 'areaM2', area_now, 'parcelVersion', parcel_now,
                              'holders', holders_now, 'charges', charges_now, 'restrictions', restrict_now);
END;
$$;

-- The register's proposal (V21), with the rights' precise codes and the usufruct's term.
CREATE OR REPLACE FUNCTION register_propose(p_office uuid, p_title uuid, p_kind text, p_expected int, p_authority_type text, p_authority_reference text,
                                 p_reason text, p_evidence_references text[], p_evidence_versions uuid[], p_transaction uuid, p_target uuid,
                                 p_area numeric, p_parcel_version uuid, p_holders jsonb, p_charge jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    title     land_title;
    target    inscription;
    state     jsonb;
    new_id    uuid;
    effective text := p_kind;
BEGIN
    IF NOT holds_at(actor, 'register.propose', p_office) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF p_kind = 'AREA_REDUCTION' THEN
        PERFORM register_refuse('KIND_NOT_AVAILABLE');
    END IF;
    IF (p_kind = 'CREATION') <> (p_title IS NULL) THEN
        PERFORM register_refuse('TITLE_REQUIRED');
    END IF;
    IF p_kind = 'CREATION' THEN
        IF p_expected <> 0 THEN
            PERFORM register_refuse('TITLE_CHANGED');
        END IF;
        IF p_area IS NULL OR p_parcel_version IS NULL
           OR NOT EXISTS (SELECT 1 FROM parcel_version v JOIN parcel c ON c.id = v.parcel_id
                           WHERE v.id = p_parcel_version AND c.office_id = p_office) THEN
            PERFORM register_refuse('PARCEL_REQUIRED');
        END IF;
    ELSE
        SELECT * INTO title FROM land_title WHERE id = p_title FOR UPDATE;
        IF title.id IS NULL OR title.office_id <> p_office THEN
            PERFORM register_refuse('TITLE_NOT_FOUND');
        END IF;
        IF title_current_version(p_title) <> p_expected THEN
            PERFORM register_refuse('TITLE_CHANGED');
        END IF;
        state := title_state(p_title, p_expected);
    END IF;

    IF p_kind IN ('CHARGE_RELEASE', 'RESTRICTION_RELEASE', 'CANCELLATION', 'CORRECTION') THEN
        SELECT * INTO target FROM inscription WHERE id = p_target AND title_id = p_title AND status = 'REGISTERED';
        IF target.id IS NULL THEN
            PERFORM register_refuse('TARGET_NOT_FOUND');
        END IF;
        IF EXISTS (SELECT 1 FROM inscription c WHERE c.kind = 'CANCELLATION' AND c.status = 'REGISTERED' AND c.target_inscription_id = target.id) THEN
            PERFORM register_refuse('TARGET_CANCELLED');
        END IF;
        effective := target.kind;
    ELSIF p_target IS NOT NULL THEN
        PERFORM register_refuse('TARGET_NOT_APPLICABLE');
    END IF;
    IF p_kind = 'CHARGE_RELEASE' AND (target.kind <> 'CHARGE' OR NOT state->'charges' @> jsonb_build_array(jsonb_build_object('inscription', target.id)))
       OR p_kind = 'RESTRICTION_RELEASE' AND (target.kind <> 'RESTRICTION' OR NOT state->'restrictions' @> jsonb_build_array(jsonb_build_object('inscription', target.id))) THEN
        PERFORM register_refuse('TARGET_NOT_ACTIVE');
    END IF;
    IF p_kind = 'CANCELLATION' AND target.kind IN ('CREATION', 'CANCELLATION', 'CORRECTION') THEN
        PERFORM register_refuse('TARGET_NOT_CANCELLABLE');
    END IF;
    IF p_kind = 'CORRECTION' AND target.kind NOT IN ('CREATION', 'TRANSFER', 'CHARGE', 'RESTRICTION') THEN
        PERFORM register_refuse('TARGET_NOT_CORRECTABLE');
    END IF;
    -- A closed title takes nothing but the cancellation of its closure.
    IF p_kind <> 'CREATION' AND state->>'status' = 'CLOSED' AND NOT (p_kind = 'CANCELLATION' AND target.kind = 'CLOSURE') THEN
        PERFORM register_refuse('TITLE_CLOSED');
    END IF;
    IF (p_kind = 'TRANSFER') <> (p_transaction IS NOT NULL) THEN
        PERFORM register_refuse('TRANSACTION_REQUIRED');
    END IF;
    IF p_kind = 'TRANSFER' AND NOT EXISTS (SELECT 1 FROM pending_transaction t WHERE t.id = p_transaction AND t.title_id = p_title AND t.status = 'HOLDING') THEN
        PERFORM register_refuse('TRANSACTION_NOT_HOLDING');
    END IF;
    IF p_kind NOT IN ('CREATION', 'CORRECTION') AND (p_area IS NOT NULL OR p_parcel_version IS NOT NULL)
       OR p_kind = 'CORRECTION' AND target.kind <> 'CREATION' AND (p_area IS NOT NULL OR p_parcel_version IS NOT NULL) THEN
        PERFORM register_refuse('AREA_NOT_APPLICABLE');
    END IF;
    IF p_kind = 'CORRECTION' AND p_parcel_version IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM parcel_version v JOIN parcel c ON c.id = v.parcel_id WHERE v.id = p_parcel_version AND c.office_id = p_office) THEN
        PERFORM register_refuse('PARCEL_REQUIRED');
    END IF;

    -- What this inscription carries follows the kind it records, or the kind it corrects.
    IF effective IN ('CREATION', 'TRANSFER') AND p_kind IN ('CREATION', 'TRANSFER', 'CORRECTION') THEN
        IF p_holders IS NULL OR jsonb_typeof(p_holders) <> 'array' OR jsonb_array_length(p_holders) = 0 THEN
            PERFORM register_refuse('HOLDERS_REQUIRED');
        END IF;
        IF EXISTS (SELECT 1 FROM jsonb_to_recordset(p_holders) AS h(party uuid, interest text, "shareNum" int, "shareDen" int)
                    WHERE h."shareNum" IS NULL OR h."shareDen" IS NULL OR h."shareNum" < 1 OR h."shareDen" < 1 OR h."shareNum" > h."shareDen"
                       OR h.party IS NULL OR h.interest IS NULL
                       OR NOT EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = h.party AND rp.office_id = p_office)) THEN
            PERFORM register_refuse('HOLDERS_INVALID');
        END IF;
        -- The rights (ADR-043, owner's review): sole ownership, co-ownership, or usufruct and bare ownership with its term.
        IF holder_rights_problem(p_holders) IS NOT NULL THEN
            PERFORM register_refuse(holder_rights_problem(p_holders));
        END IF;
    ELSIF p_holders IS NOT NULL AND jsonb_typeof(p_holders) = 'array' AND jsonb_array_length(p_holders) > 0 THEN
        PERFORM register_refuse('HOLDERS_NOT_APPLICABLE');
    END IF;
    IF effective IN ('CHARGE', 'RESTRICTION') AND p_kind IN ('CHARGE', 'RESTRICTION', 'CORRECTION') THEN
        IF p_charge IS NULL OR p_charge->>'kind' IS NULL THEN
            PERFORM register_refuse('CHARGE_REQUIRED');
        END IF;
        IF (effective = 'CHARGE' AND p_charge->>'kind' NOT IN ('MORTGAGE', 'PRIVILEGE', 'SERVITUDE', 'OTHER_CHARGE'))
           OR (effective = 'RESTRICTION' AND p_charge->>'kind' NOT IN ('PRENOTATION', 'OPPOSITION', 'JUDICIAL_SEIZURE', 'ADMINISTRATIVE'))
           OR (effective = 'CHARGE' AND coalesce((p_charge->>'blocksTransfer')::boolean, false)) THEN
            PERFORM register_refuse('CHARGE_INVALID');
        END IF;
        IF p_charge->>'beneficiary' IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = (p_charge->>'beneficiary')::uuid AND rp.office_id = p_office) THEN
            PERFORM register_refuse('CHARGE_INVALID');
        END IF;
    ELSIF p_charge IS NOT NULL AND jsonb_typeof(p_charge) = 'object' THEN
        PERFORM register_refuse('CHARGE_NOT_APPLICABLE');
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(coalesce(p_evidence_versions, '{}')) e(v)
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv WHERE dv.id = e.v AND dv.status = 'STORED')) THEN
        PERFORM register_refuse('EVIDENCE_NOT_FOUND');
    END IF;

    INSERT INTO inscription (title_id, new_title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
                             evidence_references, evidence_versions, completes_transaction_id, target_inscription_id, area_m2,
                             parcel_version_id, proposed_by)
    VALUES (p_title, CASE WHEN p_kind = 'CREATION' THEN uuidv7() END, p_office, p_kind, p_expected, p_authority_type,
            btrim(p_authority_reference), btrim(p_reason), p_evidence_references, coalesce(p_evidence_versions, '{}'), p_transaction,
            p_target, p_area, p_parcel_version, actor)
    RETURNING id INTO new_id;
    IF effective IN ('CREATION', 'TRANSFER') AND p_kind IN ('CREATION', 'TRANSFER', 'CORRECTION') THEN
        INSERT INTO inscription_holder (inscription_id, party_id, interest, share_num, share_den, usufruct_term, usufruct_until)
        SELECT new_id, h.party, h.interest, h."shareNum", h."shareDen", h."usufructTerm", h."usufructUntil"
          FROM jsonb_to_recordset(p_holders) AS h(party uuid, interest text, "shareNum" int, "shareDen" int, "usufructTerm" text,
                                                  "usufructUntil" date);
    END IF;
    IF effective IN ('CHARGE', 'RESTRICTION') AND p_kind IN ('CHARGE', 'RESTRICTION', 'CORRECTION') THEN
        INSERT INTO inscription_charge (inscription_id, charge_kind, beneficiary_id, amount, blocks_transfer, ends_on, note)
        VALUES (new_id, p_charge->>'kind', (p_charge->>'beneficiary')::uuid, (p_charge->>'amount')::numeric,
                coalesce((p_charge->>'blocksTransfer')::boolean, false), (p_charge->>'endsOn')::date, p_charge->>'note');
    END IF;
    IF register_actor_interested(actor, p_title, new_id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN new_id;
END;
$$;


-- Whoever handled the case at any step, persons included.
CREATE OR REPLACE FUNCTION register_case_handler(p_application uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND (
        EXISTS (SELECT 1 FROM application a WHERE a.id = p_application AND a.applicant_id = p_actor)
        OR EXISTS (SELECT 1 FROM case_person cp WHERE cp.application_id = p_application AND cp.account_id = p_actor)
        OR EXISTS (SELECT 1 FROM completeness_record r WHERE r.application_id = p_application AND r.actor_id = p_actor)
        OR EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.assignee_id = p_actor)
        OR EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = p_application AND s.actor_id = p_actor)
        OR EXISTS (SELECT 1 FROM decision d WHERE d.application_id = p_application AND d.decided_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = p_application AND r.recorded_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_objection o WHERE o.application_id = p_application AND o.recorded_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_objection o JOIN procedure_objection_decision od ON od.objection_id = o.id
                    WHERE o.application_id = p_application AND od.decided_by = p_actor)
        OR EXISTS (SELECT 1 FROM case_party_file f WHERE f.application_id = p_application AND f.uploaded_by = p_actor)
        OR EXISTS (SELECT 1 FROM case_person_identity i WHERE i.application_id = p_application AND i.entered_by = p_actor)
        OR EXISTS (SELECT 1 FROM case_ownership_statement s WHERE s.application_id = p_application AND s.entered_by = p_actor)
        OR EXISTS (SELECT 1 FROM case_mandate m WHERE m.application_id = p_application AND m.entered_by = p_actor)
        OR EXISTS (SELECT 1 FROM case_party_check c WHERE c.application_id = p_application AND c.checked_by = p_actor))
$$;

-- Step 5's checks, and the persons: as approved, and unchanged since.
CREATE OR REPLACE FUNCTION register_case_problems(p_application uuid, p_except uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a         application;
    d         decision;
    proc      jsonb;
    problems  text[] := ARRAY[]::text[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN ARRAY['CASE_NOT_FOUND'];
    END IF;
    proc := application_procedure(a.id);
    IF proc IS NULL OR proc ->> 'registers' IS DISTINCT FROM 'CREATION' THEN
        RETURN ARRAY['PROCEDURE_NOT_CREATION'];
    END IF;
    -- The CREATION being signed is still what was approved, or it is stale: withdrawn or rejected, and proposed anew.
    IF p_except IS NOT NULL AND register_case_proposal_stale(p_except) THEN
        RETURN ARRAY['STALE_PROPOSAL'];
    END IF;
    problems := problems || procedure_registration_problems(a.id);
    IF a.state = 'APPROVED' AND EXISTS (SELECT 1 FROM unnest(procedure_gate_problems(a.id)) g(code)
                                         WHERE g.code <> 'OBJECTION_OPEN' AND g.code NOT LIKE 'OBJECTION_REVIEW_PENDING:%') THEN
        problems := problems || 'PROCEDURE_INCOMPLETE'::text;
    END IF;
    SELECT * INTO d FROM decision WHERE application_id = a.id;
    IF a.state = 'APPROVED' THEN
        IF cardinality(case_party_problems(a.id)) > 0 OR d.ownership_statement_id IS NULL THEN
            problems := problems || 'PARTIES_INCOMPLETE'::text;
        ELSIF d.ownership_statement_id IS DISTINCT FROM (case_statement_latest(a.id)).id
              OR EXISTS (SELECT 1 FROM case_person_identity i WHERE i.application_id = a.id AND i.entered_at > d.at)
              OR EXISTS (SELECT 1 FROM case_mandate m WHERE m.application_id = a.id AND m.entered_at > d.at) THEN
            problems := problems || 'PARTIES_CHANGED_SINCE_DECISION'::text;
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id) THEN
        problems := problems || 'CASE_ALREADY_REGISTERED'::text;
    ELSIF EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
                    AND i.id IS DISTINCT FROM p_except) THEN
        problems := problems || 'CREATION_ALREADY_PROPOSED'::text;
    END IF;
    IF EXISTS (SELECT 1 FROM land_title t
                 JOIN title_version v ON v.title_id = t.id AND v.version_no = title_current_version(t.id)
                 JOIN parcel_version pv ON pv.id = v.parcel_version_id
                WHERE pv.parcel_id = a.parcel_id AND v.status = 'ACTIVE') THEN
        problems := problems || 'PARCEL_ALREADY_TITLED'::text;
    ELSIF EXISTS (SELECT 1 FROM inscription i JOIN parcel_version pv ON pv.id = i.parcel_version_id
                   WHERE i.kind = 'CREATION' AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING') AND pv.parcel_id = a.parcel_id
                     AND i.application_id IS DISTINCT FROM a.id AND i.id IS DISTINCT FROM p_except) THEN
        problems := problems || 'PARCEL_CREATION_PENDING'::text;
    END IF;
    RETURN problems;
END;
$$;

-- The proposal takes no holder any more (ADR-043 D36): the holders are the decided statement's owners, each a party of the
-- office from their latest checked identity, with the statement's interest and exact share.
DROP FUNCTION register_propose_from_case(uuid, jsonb, text);
CREATE FUNCTION register_propose_from_case(p_application uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    d         decision;
    pv        parcel_version;
    problems  text[];
    s         record;
    party     uuid;
    new_id    uuid;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    IF a.id IS NULL OR a.office_id IS NULL OR NOT holds_at(actor, 'register.read', a.office_id) THEN
        PERFORM register_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, 'register.propose', a.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF register_case_handler(a.id, actor) THEN
        PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
    END IF;
    problems := register_case_problems(a.id, NULL);
    IF cardinality(problems) > 0 THEN
        PERFORM register_refuse(problems[1]);
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    SELECT * INTO d FROM decision WHERE application_id = a.id;
    SELECT v.* INTO pv FROM parcel_version v JOIN parcel p ON p.id = v.parcel_id
     WHERE p.id = a.parcel_id AND v.version_no = p.current_version_no AND p.office_id = a.office_id;
    IF pv.id IS NULL THEN
        PERFORM register_refuse('PARCEL_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(d.cited_versions) e(v)
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv WHERE dv.id = e.v AND dv.status = 'STORED')) THEN
        PERFORM register_refuse('EVIDENCE_NOT_FOUND');
    END IF;
    INSERT INTO inscription (title_id, new_title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
                             evidence_references, evidence_versions, area_m2, parcel_version_id, proposed_by, application_id, decision_id,
                             ownership_statement_id, ownership_statement_version)
    VALUES (NULL, uuidv7(), a.office_id, 'CREATION', 0, 'DECISION', a.reference, btrim(p_reason),
            ARRAY[a.reference, 'DECISION:' || d.id::text, 'OWNERSHIP_STATEMENT:' || d.ownership_statement_id::text], d.cited_versions,
            pv.area_m2, pv.id, actor, a.id, d.id, d.ownership_statement_id,
            (SELECT version_no FROM case_ownership_statement WHERE id = d.ownership_statement_id))
    RETURNING id INTO new_id;
    FOR s IN SELECT sh.interest, sh.share_num, sh.share_den, sh.usufruct_term, sh.usufruct_until, cp.kind, cp.account_id, i.id AS identity,
                    i.version_no AS identity_version, i.full_name, i.identity_kind, i.identity_number
               FROM case_ownership_share sh
               JOIN case_person cp ON cp.id = sh.person_id
               CROSS JOIN LATERAL case_identity_latest(cp.id) i
              WHERE sh.statement_id = d.ownership_statement_id
              ORDER BY cp.ref, sh.interest LOOP
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, account_id, created_by, source_identity_id,
                                    source_identity_version)
        VALUES (a.office_id, s.kind, s.full_name, s.identity_kind, s.identity_number, s.account_id, actor, s.identity, s.identity_version)
        RETURNING id INTO party;
        INSERT INTO inscription_holder (inscription_id, party_id, interest, share_num, share_den, usufruct_term, usufruct_until)
        VALUES (new_id, party, s.interest, s.share_num, s.share_den, s.usufruct_term, s.usufruct_until);
    END LOOP;
    IF register_actor_interested(actor, NULL, new_id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN new_id;
END;
$$;

-- Whether an inscription is under dual control: ADR-040's kinds, and a CREATION from a case (D40).
CREATE FUNCTION inscription_dual_control(p_kind text, p_application uuid) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_kind IN ('CLOSURE', 'CANCELLATION', 'CORRECTION') OR (p_kind = 'CREATION' AND p_application IS NOT NULL)
$$;

CREATE OR REPLACE FUNCTION register_register(p_inscription uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    i     inscription := register_lock(p_inscription, 'register.inscribe', actor);
BEGIN
    IF inscription_dual_control(i.kind, i.application_id) THEN
        PERFORM register_refuse('DUAL_CONTROL_REQUIRED');
    END IF;
    IF i.status <> 'PROPOSED' THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    PERFORM register_store_signature(p_inscription, 'REGISTERED', actor, p_canonical, p_signature);
    PERFORM register_apply(p_inscription, actor);
END;
$$;

CREATE OR REPLACE FUNCTION register_request_confirmation(p_inscription uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    i        inscription := register_lock(p_inscription, 'register.propose', actor);
    problems text[];
BEGIN
    IF NOT inscription_dual_control(i.kind, i.application_id) THEN
        PERFORM register_refuse('DUAL_CONTROL_NOT_APPLICABLE');
    END IF;
    IF i.status <> 'PROPOSED' THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    IF i.proposed_by <> actor THEN
        PERFORM register_refuse('NOT_THE_PROPOSER');
    END IF;
    IF i.application_id IS NOT NULL THEN
        -- The first signature is checked against the case as the second will be.
        PERFORM 1 FROM application WHERE id = i.application_id FOR UPDATE;
        IF register_case_handler(i.application_id, actor) THEN
            PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
        END IF;
        problems := register_case_problems(i.application_id, i.id);
        IF cardinality(problems) > 0 THEN
            PERFORM register_refuse(problems[1]);
        END IF;
    END IF;
    PERFORM register_store_signature(p_inscription, 'PROPOSED', actor, p_canonical, p_signature);
    UPDATE inscription SET status = 'CONFIRMATION_PENDING' WHERE id = p_inscription;
END;
$$;

-- register_apply (V34) checks the actor; with two signers, the proposer is checked again too.
CREATE FUNCTION register_case_signers_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.status = 'REGISTERED' AND OLD.status <> 'REGISTERED' AND NEW.application_id IS NOT NULL
       AND register_case_handler(NEW.application_id, NEW.proposed_by) THEN
        PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER register_case_signers_guard BEFORE UPDATE OF status ON inscription
    FOR EACH ROW EXECUTE FUNCTION register_case_signers_guard();

-- "Approved, to register" also says whom the title will name, from the decided statement.
DROP FUNCTION register_case_queue(int, int);
CREATE FUNCTION register_case_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, parcel_reference text, decided_at timestamptz,
               problems text[], may_propose boolean, inscription_id uuid, inscription_status text, owners text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.id, a.reference::text, f.code, a.type_code, a.parcel_reference::text, d.at,
           register_case_problems(a.id, NULL),
           cardinality(register_case_problems(a.id, NULL)) = 0 AND NOT register_case_handler(a.id, current_actor()),
           i.id, i.status,
           (SELECT string_agg(format('%s (%s/%s)', (case_identity_latest(cp.id)).full_name, sh.share_num, sh.share_den), '; '
                              ORDER BY cp.ref)
              FROM case_ownership_share sh JOIN case_person cp ON cp.id = sh.person_id
             WHERE sh.statement_id = d.ownership_statement_id)
      FROM application a
      JOIN office f ON f.id = a.office_id
      JOIN decision d ON d.application_id = a.id AND d.outcome = 'APPROVED'
      LEFT JOIN inscription i ON i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
     WHERE a.state = 'APPROVED'
       AND application_procedure(a.id) ->> 'registers' = 'CREATION'
       AND holds_at(current_actor(), 'register.propose', a.office_id)
       AND NOT EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = a.id)
     ORDER BY d.at, a.id
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)
$$;


-- --- Retention: every case document has its class; legal holds on them and on whole cases (owner's review) -----------------

-- An applicant's document takes its class from its category: identity, evidence of a right, or anything else.
CREATE TABLE document_category_retention (
    category         text PRIMARY KEY CHECK (category ~ '^DEMO_[A-Z0-9_]{1,40}$'),
    retention_class  text NOT NULL REFERENCES retention_class (code)
);
INSERT INTO document_category_retention (category, retention_class) VALUES
    ('DEMO_IDENTITY', 'APPLICATION_IDENTITY_DOCUMENT'),
    ('DEMO_PROOF_OF_RIGHT', 'APPLICATION_OWNERSHIP_EVIDENCE'),
    ('DEMO_DEVELOPMENT_EVIDENCE', 'APPLICATION_OWNERSHIP_EVIDENCE'),
    ('DEMO_SITE_PLAN', 'APPLICATION_SUPPORTING_DOCUMENT');
CREATE TRIGGER document_category_retention_append_only BEFORE UPDATE OR DELETE ON document_category_retention
    FOR EACH ROW EXECUTE FUNCTION reject_modification();

ALTER TABLE document_version ADD COLUMN retention_class text NOT NULL DEFAULT 'APPLICATION_SUPPORTING_DOCUMENT' REFERENCES retention_class (code);
ALTER TABLE document_version DISABLE TRIGGER document_version_immutable;
UPDATE document_version v SET retention_class = r.retention_class
  FROM document d JOIN document_category_retention r ON r.category = d.category WHERE d.id = v.document_id;
ALTER TABLE document_version ENABLE TRIGGER document_version_immutable;

CREATE FUNCTION document_version_retention() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    NEW.retention_class := coalesce((SELECT r.retention_class FROM document d JOIN document_category_retention r ON r.category = d.category
                                      WHERE d.id = NEW.document_id), 'APPLICATION_SUPPORTING_DOCUMENT');
    RETURN NEW;
END;
$$;
CREATE TRIGGER document_version_retention BEFORE INSERT ON document_version FOR EACH ROW EXECUTE FUNCTION document_version_retention();
CREATE TRIGGER document_version_retention_fixed BEFORE UPDATE ON document_version FOR EACH ROW EXECUTE FUNCTION retention_class_fixed();

-- Holds on each case document, and on a whole case: a case's hold covers every document it has, and every one added after.
ALTER TABLE legal_hold DROP CONSTRAINT legal_hold_subject_kind_check;
ALTER TABLE legal_hold ADD CONSTRAINT legal_hold_subject_kind_check CHECK (subject_kind IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT',
    'DEPOSIT', 'PROFESSIONAL_EVIDENCE', 'PROFESSIONAL', 'CASE', 'APPLICATION_DOCUMENT', 'CASE_PARTY_FILE', 'PROCEDURE_STAGE_FILE',
    'PROCEDURE_OBJECTION_FILE'));

-- The case a case document belongs to; NULL for any other kind.
CREATE FUNCTION record_case_of(p_kind text, p_id uuid) RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_kind
        WHEN 'APPLICATION_DOCUMENT' THEN (SELECT d.application_id FROM document_version v JOIN document d ON d.id = v.document_id WHERE v.id = p_id)
        WHEN 'CASE_PARTY_FILE' THEN (SELECT application_id FROM case_party_file WHERE id = p_id)
        WHEN 'PROCEDURE_STAGE_FILE' THEN (SELECT r.application_id FROM procedure_stage_file f JOIN procedure_stage_record r ON r.id = f.record_id
                                           WHERE f.id = p_id)
        WHEN 'PROCEDURE_OBJECTION_FILE' THEN (SELECT o.application_id FROM procedure_objection_file f JOIN procedure_objection o ON o.id = f.objection_id
                                               WHERE f.id = p_id)
    END
$$;

CREATE OR REPLACE FUNCTION legal_hold_subject_exists(p_kind text, p_id uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_kind
        WHEN 'DEPOSIT_DOCUMENT' THEN EXISTS (SELECT 1 FROM deposit_document WHERE id = p_id)
        WHEN 'DEPOSIT_DOCUMENT_REPLACEMENT' THEN EXISTS (SELECT 1 FROM deposit_document_replacement WHERE id = p_id)
        WHEN 'DEPOSIT' THEN EXISTS (SELECT 1 FROM pending_transaction WHERE id = p_id)
        WHEN 'PROFESSIONAL_EVIDENCE' THEN EXISTS (SELECT 1 FROM professional_verification_document WHERE id = p_id)
        WHEN 'PROFESSIONAL' THEN EXISTS (SELECT 1 FROM professional_profile WHERE user_id = p_id)
        WHEN 'CASE' THEN EXISTS (SELECT 1 FROM application WHERE id = p_id AND state <> 'DRAFT')
        WHEN 'APPLICATION_DOCUMENT' THEN EXISTS (SELECT 1 FROM document_version WHERE id = p_id)
        WHEN 'CASE_PARTY_FILE' THEN EXISTS (SELECT 1 FROM case_party_file WHERE id = p_id)
        WHEN 'PROCEDURE_STAGE_FILE' THEN EXISTS (SELECT 1 FROM procedure_stage_file WHERE id = p_id)
        WHEN 'PROCEDURE_OBJECTION_FILE' THEN EXISTS (SELECT 1 FROM procedure_objection_file WHERE id = p_id)
        ELSE false
    END
$$;

CREATE OR REPLACE FUNCTION record_holds(p_kind text, p_id uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT h.id FROM legal_hold h
     WHERE legal_hold_in_force(h.id)
       AND ((h.subject_kind = p_kind AND h.subject_id = p_id)
            OR (h.subject_kind = 'DEPOSIT' AND p_kind = 'DEPOSIT_DOCUMENT'
                AND h.subject_id = (SELECT transaction_id FROM deposit_document WHERE id = p_id))
            OR (h.subject_kind = 'DEPOSIT' AND p_kind = 'DEPOSIT_DOCUMENT_REPLACEMENT'
                AND h.subject_id = (SELECT transaction_id FROM deposit_document_replacement WHERE id = p_id))
            OR (h.subject_kind = 'PROFESSIONAL' AND p_kind = 'PROFESSIONAL_EVIDENCE'
                AND h.subject_id = (SELECT v.user_id FROM professional_verification_document d
                                      JOIN professional_verification v ON v.id = d.verification_id WHERE d.id = p_id))
            -- A case's hold covers every document of the case, whenever it was added.
            OR (h.subject_kind = 'CASE' AND h.subject_id = record_case_of(p_kind, p_id)))
$$;

CREATE OR REPLACE FUNCTION record_retention_class(p_kind text, p_id uuid) RETURNS text
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_kind
        WHEN 'DEPOSIT_DOCUMENT' THEN (SELECT retention_class FROM deposit_document WHERE id = p_id)
        WHEN 'DEPOSIT_DOCUMENT_REPLACEMENT' THEN (SELECT retention_class FROM deposit_document_replacement WHERE id = p_id)
        WHEN 'PROFESSIONAL_EVIDENCE' THEN (SELECT retention_class FROM professional_verification_document WHERE id = p_id)
        WHEN 'APPLICATION_DOCUMENT' THEN (SELECT retention_class FROM document_version WHERE id = p_id)
        WHEN 'CASE_PARTY_FILE' THEN (SELECT retention_class FROM case_party_file WHERE id = p_id)
        WHEN 'PROCEDURE_STAGE_FILE' THEN (SELECT retention_class FROM procedure_stage_file WHERE id = p_id)
        WHEN 'PROCEDURE_OBJECTION_FILE' THEN (SELECT retention_class FROM procedure_objection_file WHERE id = p_id)
    END
$$;

CREATE OR REPLACE FUNCTION legal_hold_place(p_kind text, p_subject uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor  uuid := current_actor();
    new_id uuid;
BEGIN
    IF NOT holds_permission(actor, 'records.hold.place') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'DEPOSIT', 'PROFESSIONAL_EVIDENCE', 'PROFESSIONAL',
                                        'CASE', 'APPLICATION_DOCUMENT', 'CASE_PARTY_FILE', 'PROCEDURE_STAGE_FILE', 'PROCEDURE_OBJECTION_FILE') THEN
        PERFORM retention_refuse('SUBJECT_KIND_INVALID');
    END IF;
    IF p_subject IS NULL OR NOT legal_hold_subject_exists(p_kind, p_subject) THEN
        PERFORM retention_refuse('SUBJECT_NOT_FOUND');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM retention_refuse('REASON_REQUIRED');
    END IF;
    INSERT INTO legal_hold (subject_kind, subject_id, reason, placed_by) VALUES (p_kind, p_subject, btrim(p_reason), actor)
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;


-- One record's retention, for the documents of a case too.
CREATE OR REPLACE FUNCTION record_retention(p_kind text, p_id uuid)
    RETURNS TABLE (retention_class text, standing text, schedule_reference text, schedule_approved_on date, holds_in_force bigint,
                   disposal_allowed boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT holds_permission(current_actor(), 'records.retention.read') THEN
        PERFORM retention_refuse('RECORDS_FORBIDDEN');
    END IF;
    IF p_kind NOT IN ('DEPOSIT_DOCUMENT', 'DEPOSIT_DOCUMENT_REPLACEMENT', 'PROFESSIONAL_EVIDENCE', 'APPLICATION_DOCUMENT', 'CASE_PARTY_FILE',
                      'PROCEDURE_STAGE_FILE', 'PROCEDURE_OBJECTION_FILE') OR NOT legal_hold_subject_exists(p_kind, p_id) THEN
        PERFORM retention_refuse('SUBJECT_NOT_FOUND');
    END IF;
    RETURN QUERY
        SELECT c.code, c.standing, c.schedule_reference, c.schedule_approved_on, (SELECT count(*) FROM record_holds(p_kind, p_id)),
               record_disposal_allowed(p_kind, p_id)
          FROM retention_class c WHERE c.code = record_retention_class(p_kind, p_id);
END;
$$;

-- --- The conservators' evidence: read-only, for the registration task only (owner's review) ------------------------------

-- What two conservators examine before signing a CREATION from a case: the decided statement and its check; each owner's
-- identity as transcribed (the version, who transcribed and who checked it, its document's validity and any exception),
-- right and share; the mandates; the documents cited. Never a phone, a postal address or a check's note on an earlier
-- version. NULL unless the reader proposes, registers or confirms at the case's office and the case is approved and
-- creates a title. The application audits each read.
CREATE FUNCTION register_case_evidence(p_application uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_build_object(
        'reference', a.reference,
        'capacity', a.declaration ->> 'capacity',
        'decision', jsonb_build_object('id', d.id, 'at', d.at, 'statement', d.ownership_statement_id),
        'statement', (SELECT jsonb_build_object('id', s.id, 'version', s.version_no, 'enteredBy', u.display_name, 'enteredAt', s.entered_at,
                             'verification', s.verification, 'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                                                         FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                                                        WHERE c.target_kind = 'OWNERSHIP' AND c.target_id = s.id))
                        FROM case_ownership_statement s JOIN app_user u ON u.id = s.entered_by WHERE s.id = d.ownership_statement_id),
        'owners', (SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                        'ref', cp.ref, 'kind', cp.kind, 'fullName', i.full_name, 'identityKind', i.identity_kind, 'identityNumber', i.identity_number,
                        'validUntil', i.valid_until, 'expiredAtTranscription', i.expired_at_transcription,
                        'exception', (SELECT jsonb_build_object('policy', x.policy, 'reason', x.reason, 'by', xu.display_name, 'at', x.authorised_at)
                                        FROM case_party_exception x JOIN app_user xu ON xu.id = x.authorised_by
                                       WHERE x.target_kind = 'IDENTITY' AND x.target_id = i.id),
                        'interest', sh.interest, 'shareNum', sh.share_num, 'shareDen', sh.share_den, 'usufructTerm', sh.usufruct_term,
                        'usufructUntil', sh.usufruct_until, 'identityVersionId', i.id, 'identityVersion', i.version_no, 'verification', i.verification,
                        'transcribedBy', iu.display_name, 'transcribedAt', i.entered_at, 'sourceFile', i.source_file_id, 'sourceVersion', i.source_version_id,
                        'basisFile', sh.basis_file_id, 'basisVersion', sh.basis_version_id,
                        'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                    FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                   WHERE c.target_kind = 'IDENTITY' AND c.target_id = i.id))) ORDER BY cp.ref, sh.interest), '[]')
                     FROM case_ownership_share sh JOIN case_person cp ON cp.id = sh.person_id
                     CROSS JOIN LATERAL case_identity_latest(cp.id) i JOIN app_user iu ON iu.id = i.entered_by
                    WHERE sh.statement_id = d.ownership_statement_id),
        'mandates', (SELECT coalesce(jsonb_agg(jsonb_build_object('representative', (case_identity_latest(cp.id)).full_name, 'scope', m.scope,
                        'represents', (SELECT jsonb_agg((case_identity_latest(q.id)).full_name ORDER BY q.ref)
                                         FROM case_mandate_subject ms JOIN case_person q ON q.id = ms.person_id WHERE ms.mandate_id = m.id),
                        'documentFile', m.document_file_id, 'version', m.version_no,
                        'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                    FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                   WHERE c.target_kind = 'MANDATE' AND c.target_id = m.id)) ORDER BY cp.ref), '[]')
                       FROM case_person cp CROSS JOIN LATERAL case_mandate_latest(cp.id) m WHERE cp.application_id = a.id AND m.id IS NOT NULL),
        'files', (SELECT coalesce(jsonb_agg(jsonb_build_object('id', f.id, 'purpose', f.purpose, 'name', f.original_name, 'sha256', f.sha256,
                        'mediaType', f.detected_type, 'sizeBytes', f.size_bytes) ORDER BY f.uploaded_at, f.id), '[]')
                    FROM case_party_file f WHERE f.application_id = a.id))
      FROM application a
      JOIN decision d ON d.application_id = a.id AND d.outcome = 'APPROVED'
     WHERE a.id = p_application AND a.state = 'APPROVED' AND case_parties_required(a.id)
       AND (holds_at(current_actor(), 'register.propose', a.office_id) OR holds_at(current_actor(), 'register.inscribe', a.office_id)
            OR holds_at(current_actor(), 'register.confirm', a.office_id))
$$;

-- A document cited by that evidence, for its download: a counter file of the case, or a stored version of the case's own
-- documents cited as an owner's identity source or a share's basis. Same readers; the application checks the hash and
-- audits the read.
CREATE FUNCTION register_case_evidence_file(p_application uuid, p_file uuid)
RETURNS TABLE (id uuid, storage_key text, sha256 text, size_bytes bigint, media_type text, original_name text, kind text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT f.id, f.storage_key::text, f.sha256::text, f.size_bytes, f.detected_type, f.original_name, 'CASE_PARTY_FILE'
      FROM case_party_file f
     WHERE f.application_id = p_application AND f.id = p_file AND register_case_evidence(p_application) IS NOT NULL
    UNION ALL
    SELECT v.id, v.storage_key::text, v.sha256::text, v.size_bytes, v.detected_type, v.original_name, 'APPLICATION_DOCUMENT'
      FROM document_version v JOIN document dd ON dd.id = v.document_id
     WHERE dd.application_id = p_application AND v.id = p_file AND v.status = 'STORED' AND register_case_evidence(p_application) IS NOT NULL
       AND (EXISTS (SELECT 1 FROM case_person_identity i WHERE i.application_id = p_application AND i.source_version_id = v.id)
            OR EXISTS (SELECT 1 FROM case_ownership_share sh JOIN case_ownership_statement s ON s.id = sh.statement_id
                        WHERE s.application_id = p_application AND sh.basis_version_id = v.id))
$$;

-- --- Reading -----------------------------------------------------------------------------------------------------------------

-- Who reads the persons of a case: its readers at the office, its assignees and stage officers, and the officers who
-- record or check persons there. Never the applicant through the tables (application_parties() is theirs).
CREATE FUNCTION case_party_readable(p_application uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT procedure_readable(p_application, p_actor)
        OR EXISTS (SELECT 1 FROM application a WHERE a.id = p_application AND a.applicant_id <> p_actor
                     AND (holds_at(p_actor, 'application.party.record', a.office_id) OR holds_at(p_actor, 'application.party.check', a.office_id)))
$$;

-- The applicant's view: each person they declared, their roles, the name as it stands now, and where the office is
-- with it; never the officers, the files or a check's note. Every status is DEMO, not independently verified.
CREATE FUNCTION application_parties(p_application uuid)
RETURNS TABLE (ref text, roles text[], full_name text, status text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT cp.ref, ARRAY(SELECT r.role FROM case_role r WHERE r.person_id = cp.id ORDER BY r.role), i.full_name,
           CASE WHEN i.source = 'DECLARED' THEN 'DECLARED'
                WHEN i.expired_at_transcription AND NOT EXISTS (SELECT 1 FROM case_party_exception x WHERE x.target_kind = 'IDENTITY'
                                                                  AND x.target_id = i.id) THEN 'DOCUMENT_EXPIRED'
                WHEN case_check_of('IDENTITY', i.id) = 'MATCHES' THEN 'MATCHES_DOCUMENT'
                WHEN case_check_of('IDENTITY', i.id) = 'DOES_NOT_MATCH' THEN 'DOES_NOT_MATCH'
                ELSE 'RECORDED' END
      FROM application a
      JOIN case_person cp ON cp.application_id = a.id
      CROSS JOIN LATERAL case_identity_latest(cp.id) i
     WHERE a.id = p_application AND a.applicant_id = current_actor()
     ORDER BY cp.ref
$$;

-- What a case's persons still need, for the officers who read them.
CREATE FUNCTION case_party_status(p_application uuid) RETURNS text[]
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN case_party_readable(p_application, current_actor()) THEN case_party_problems(p_application) END
$$;

ALTER TABLE case_person ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_party_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_person_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_ownership_statement ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_ownership_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_mandate ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_mandate_subject ENABLE ROW LEVEL SECURITY;
ALTER TABLE case_party_check ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_person_read ON case_person FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_role_read ON case_role FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_party_file_read ON case_party_file FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_person_identity_read ON case_person_identity FOR SELECT TO snigf_app
    USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_ownership_statement_read ON case_ownership_statement FOR SELECT TO snigf_app
    USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_ownership_share_read ON case_ownership_share FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM case_ownership_statement s WHERE s.id = statement_id));
CREATE POLICY case_mandate_read ON case_mandate FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));
CREATE POLICY case_mandate_subject_read ON case_mandate_subject FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM case_mandate m WHERE m.id = mandate_id));
CREATE POLICY case_party_check_read ON case_party_check FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));

REVOKE ALL ON case_person, case_role, case_party_file, case_person_identity, case_ownership_statement, case_ownership_share, case_mandate,
              case_mandate_subject, case_party_check FROM PUBLIC;
GRANT SELECT ON case_person, case_role, case_party_file, case_person_identity, case_ownership_statement, case_ownership_share, case_mandate,
                case_mandate_subject, case_party_check TO snigf_app;

REVOKE ALL ON FUNCTION case_parties_required(uuid), case_declaration_problems(jsonb), application_declaration_problems(uuid),
    application_declaration_guard(), application_declaration_materialise(), case_identity_latest(uuid), case_statement_latest(uuid),
    case_mandate_latest(uuid), case_parties_as_declaration(uuid), case_check_of(text, uuid), case_party_problems(uuid),
    case_party_actor_problem(uuid, text, uuid), case_party_file_add(uuid, text, text, text, bigint, text, text),
    case_source_valid(uuid, text, uuid, uuid), case_identity_record(uuid, jsonb, uuid, uuid, uuid, text),
    case_ownership_record(uuid, jsonb, uuid, text), case_mandate_record(uuid, uuid[], text, uuid, uuid, text),
    case_party_check_record(text, uuid, text, text), completeness_parties_gate(), decision_parties_gate(),
    register_propose_from_case(uuid, text), inscription_dual_control(text, uuid), register_case_signers_guard(),
    register_case_queue(int, int), case_party_readable(uuid, uuid), application_parties(uuid), case_party_status(uuid),
    holder_rights_problem(jsonb), case_party_exception_record(uuid, text, text), register_case_proposal_stale(uuid),
    register_case_evidence(uuid), register_case_evidence_file(uuid, uuid), record_case_of(text, uuid), document_version_retention() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION application_declaration_problems(uuid), case_party_file_add(uuid, text, text, text, bigint, text, text),
    case_identity_record(uuid, jsonb, uuid, uuid, uuid, text), case_ownership_record(uuid, jsonb, uuid, text),
    case_mandate_record(uuid, uuid[], text, uuid, uuid, text), case_party_check_record(text, uuid, text, text),
    register_propose_from_case(uuid, text), register_case_queue(int, int), case_party_readable(uuid, uuid), application_parties(uuid),
    case_party_status(uuid), inscription_dual_control(text, uuid), case_party_exception_record(uuid, text, text),
    register_case_evidence(uuid), register_case_evidence_file(uuid, uuid) TO snigf_app;
ALTER TABLE case_party_exception ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_party_exception_read ON case_party_exception FOR SELECT TO snigf_app USING (case_party_readable(application_id, current_actor()));
REVOKE ALL ON case_party_exception, document_category_retention FROM PUBLIC;
GRANT SELECT ON case_party_exception, document_category_retention TO snigf_app;
