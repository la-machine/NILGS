-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V37: procedure B, the mutation totale (Slice 4, ADR-042 step 6, §5 as redesigned on 16 Nov 2026, D45 to D47; ADR-043
-- D36, D37). DEMO only: no sale, deed, duty or holder here is real; SNIGF computes and collects no tax (D24).
--
--   the persons of a B case    case_parties_required() covers procedure B. Its declaration names TRANSFERORS (holders of
--                              the title who transfer) and TRANSFEREES (the proposed new owners, with the interest and
--                              exact share the deed gives), kept apart (D37), and representatives with their mandates.
--                              The ownership statement of a B case is the transferees'; it is the only source of the new
--                              holders (D36). Every identity is transcribed and checked as in step 5a.
--   application.title_id       the title the case names, resolved at submission from its titleNumber at the case's office:
--                              refused when not found, closed, not resting on the case's parcel, or named by another open
--                              procedure B case. Never changed afterwards.
--   a deposit names a case     transaction_deposit() and transaction_deposit_professional() take the case's reference.
--                              Accepted only for a procedure B case being examined, on the same title, named by no other
--                              open deposit. ADR-040's lock and conflicts are unchanged.
--   TITLE_VERIFICATION         by a conservator: the title version read and, for each holder, the transferor matched by
--                              identity. Completed only when the transferors are exactly the holders (D45), each checked,
--                              with no blocking restriction, no open conflict and the case's deposit holding.
--   DUTIES_EVIDENCE            by a conservator: a TAX_OR_DUTY document of the case's holding deposit, its reference.
--   the gate                   the verification holds while the title's holders are those verified and nothing blocks (D47);
--                              the receipt holds while its deposit does.
--   the TRANSFER from a case   register_propose_transfer_from_case(): onto the case's title at its current version,
--                              completing the case's holding deposit, the holders the decided statement's transferees.
--                              Two signatures (D46), neither by anyone who handled the case or received its deposit. A
--                              title changed after the proposal makes it stale (ADR-040's TITLE_CHANGED); proposed again
--                              at the current version, it registers if the holders are still the verified ones (D47).
--   ADR-040's plain TRANSFER   never completes a deposit that names a case (DEPOSIT_OF_A_CASE).
--   reading                    "Approved, to register" lists transfers too; the conservators' evidence names the
--                              transferors; the applicant reads that the transfer is registered, its title and time.
-- Registration issues no document: the DEMO notice and the delivery are step 7 (D29).

SET search_path = snigf, public;

-- --- The persons of a procedure B case ----------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION case_parties_required(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce(application_procedure(p_application) ->> 'registers' IN ('CREATION', 'TRANSFER'), false)
$$;

-- The role whose holders the ownership statement records: the intended owners of a CREATION, the transferees of a TRANSFER.
CREATE FUNCTION case_owner_role(p_application uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE application_procedure(p_application) ->> 'registers' WHEN 'TRANSFER' THEN 'TRANSFEREE' ELSE 'INTENDED_OWNER' END
$$;

-- A declaration's problems under its procedure. Procedure A keeps V35's rules. Procedure B (D37):
--   roles APPLICANT, REPRESENTATIVE, TRANSFEROR, TRANSFEREE; never both TRANSFEROR and TRANSFEREE;
--   at least one transferor and one transferee; the shares are the transferees' and total one;
--   SELF: the applicant is a transferor or a transferee and represents nobody; ON_BEHALF_OF_ONE / _SEVERAL: the applicant is
--   a representative whose mandate covers one / several transferors or transferees; a transferor's consent is the deed.
CREATE FUNCTION case_declaration_problems(p jsonb, p_procedure text) RETURNS text[]
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems   text[] := ARRAY[]::text[];
    x          jsonb;
    refs       text[] := ARRAY[]::text[];
    transferors text[] := ARRAY[]::text[];
    transferees text[] := ARRAY[]::text[];
    reps       text[] := ARRAY[]::text[];
    applicant  text;
    applicants int := 0;
    capacity   text;
    mandated   text[] := ARRAY[]::text[];
    shared     text[] := ARRAY[]::text[];
    represented int := 0;
BEGIN
    IF p_procedure IS DISTINCT FROM 'B' THEN
        RETURN case_declaration_problems(p);
    END IF;
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
           OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(x -> 'roles') r WHERE r NOT IN ('APPLICANT', 'REPRESENTATIVE', 'TRANSFEROR', 'TRANSFEREE'))
           OR (SELECT count(*) <> count(DISTINCT r) FROM jsonb_array_elements_text(x -> 'roles') r) THEN
            problems := problems || ('PERSON_INVALID:' || (x ->> 'ref'));
            CONTINUE;
        END IF;
        IF (x -> 'roles') ? 'TRANSFEROR' AND (x -> 'roles') ? 'TRANSFEREE' THEN
            problems := problems || ('TRANSFEROR_AND_TRANSFEREE:' || (x ->> 'ref'));
            CONTINUE;
        END IF;
        IF (x -> 'roles') ? 'APPLICANT' THEN
            applicants := applicants + 1;
            applicant := x ->> 'ref';
            IF x ->> 'phone' IS NULL THEN
                problems := problems || 'PHONE_REQUIRED'::text;
            END IF;
        END IF;
        IF (x -> 'roles') ? 'TRANSFEROR' THEN
            transferors := transferors || (x ->> 'ref');
        END IF;
        IF (x -> 'roles') ? 'TRANSFEREE' THEN
            transferees := transferees || (x ->> 'ref');
        END IF;
        IF (x -> 'roles') ? 'REPRESENTATIVE' THEN
            reps := reps || (x ->> 'ref');
        END IF;
    END LOOP;
    IF applicants <> 1 THEN
        problems := problems || 'APPLICANT_REQUIRED'::text;
    END IF;
    IF cardinality(transferors) = 0 THEN
        problems := problems || 'TRANSFEROR_REQUIRED'::text;
    END IF;
    IF cardinality(transferees) = 0 THEN
        problems := problems || 'TRANSFEREE_REQUIRED'::text;
    END IF;

    -- Mandates: one per representative, of transferors or transferees other than themselves.
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
                           WHERE NOT (r = ANY (transferors) OR r = ANY (transferees)) OR r = x ->> 'representative') THEN
                problems := problems || 'MANDATE_INVALID'::text;
                CONTINUE;
            END IF;
            IF (x ->> 'representative') = ANY (mandated) THEN
                problems := problems || ('MANDATE_DUPLICATE:' || (x ->> 'representative'));
                CONTINUE;
            END IF;
            mandated := mandated || (x ->> 'representative');
            IF (x ->> 'representative') = applicant THEN
                represented := (SELECT count(DISTINCT r) FROM jsonb_array_elements_text(x -> 'represents') r);
            END IF;
        END LOOP;
    END IF;
    problems := problems || ARRAY(SELECT 'REPRESENTATIVE_WITHOUT_MANDATE:' || r FROM unnest(reps) r WHERE NOT r = ANY (mandated) ORDER BY r);
    IF applicants = 1 AND capacity IS NOT NULL AND (
           (capacity = 'SELF' AND (NOT (applicant = ANY (transferors) OR applicant = ANY (transferees)) OR applicant = ANY (reps)))
        OR (capacity = 'ON_BEHALF_OF_ONE' AND (NOT applicant = ANY (reps) OR represented <> 1))
        OR (capacity = 'ON_BEHALF_OF_SEVERAL' AND (NOT applicant = ANY (reps) OR represented < 2))) THEN
        problems := problems || 'CAPACITY_MISMATCH'::text;
    END IF;

    -- Shares: the transferees', exact fractions totalling one, as the deed gives them; nobody else has one.
    IF jsonb_typeof(p -> 'shares') IS DISTINCT FROM 'array' OR jsonb_array_length(p -> 'shares') = 0 THEN
        problems := problems || 'SHARES_REQUIRED'::text;
    ELSE
        FOR x IN SELECT value FROM jsonb_array_elements(p -> 'shares') LOOP
            IF jsonb_typeof(x) <> 'object' OR NOT coalesce(x ->> 'person', '') = ANY (transferees)
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
        problems := problems || ARRAY(SELECT 'OWNER_WITHOUT_SHARE:' || o FROM unnest(transferees) o WHERE NOT o = ANY (shared) ORDER BY o);
    END IF;
    RETURN problems;
END;
$$;

CREATE OR REPLACE FUNCTION application_declaration_problems(p_application uuid) RETURNS text[]
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN case_parties_required(a.id) THEN case_declaration_problems(a.declaration, application_procedure(a.id) ->> 'code')
                ELSE ARRAY[]::text[] END
      FROM application a
     WHERE a.id = p_application AND a.applicant_id = current_actor()
$$;

CREATE OR REPLACE FUNCTION application_declaration_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems text[];
BEGIN
    IF OLD.state <> 'DRAFT' AND NEW.declaration IS DISTINCT FROM OLD.declaration THEN
        RAISE EXCEPTION 'application % was submitted: its declaration never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED' AND case_parties_required(NEW.id) THEN
        problems := case_declaration_problems(NEW.declaration, application_procedure(NEW.id) ->> 'code');
        IF cardinality(problems) > 0 THEN
            PERFORM procedure_refuse('DECLARATION_INVALID');
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION case_party_problems(p_application uuid) RETURNS text[]
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
    problems := case_declaration_problems(case_parties_as_declaration(p_application), application_procedure(p_application) ->> 'code');
    FOR p IN SELECT cp.ref, i.id, i.source, i.expired_at_transcription FROM case_person cp CROSS JOIN LATERAL case_identity_latest(cp.id) i
              WHERE cp.application_id = p_application ORDER BY cp.ref LOOP
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

-- The ownership statement records the case's owner role: intended owners (A), transferees (B).
CREATE OR REPLACE FUNCTION case_ownership_record(p_application uuid, p_shares jsonb, p_supersedes uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    latest   case_ownership_statement;
    problem  text;
    new_id   uuid;
    sh       jsonb;
    owner_role text := case_owner_role(p_application);
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
                                                      AND r.role = owner_role)
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
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_shares) x WHERE (x ->> 'usufructUntil')::date <= current_date) THEN
        PERFORM procedure_refuse('USUFRUCT_TERM_INVALID');
    END IF;
    IF EXISTS (SELECT 1 FROM case_role r WHERE r.application_id = p_application AND r.role = owner_role
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

-- A representative's mandate: of intended owners (A), or of transferors and transferees (B), never themselves.
CREATE OR REPLACE FUNCTION case_mandate_record(p_representative uuid, p_represents uuid[], p_scope text, p_document_file uuid, p_supersedes uuid,
                                               p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    cp       case_person;
    latest   case_mandate;
    problem  text;
    new_id   uuid;
    roles    text[];
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
    roles := CASE case_owner_role(cp.application_id) WHEN 'TRANSFEREE' THEN ARRAY['TRANSFEROR', 'TRANSFEREE'] ELSE ARRAY['INTENDED_OWNER'] END;
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
                                                                          AND x.application_id = cp.application_id AND x.role = ANY (roles))) THEN
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

-- --- The title of a case ---------------------------------------------------------------------------------------------------

ALTER TABLE application ADD COLUMN title_id uuid REFERENCES land_title (id);
CREATE INDEX ix_application_title ON application (title_id) WHERE title_id IS NOT NULL;

-- Whether a procedure B case on a title is still open: being examined, or approved with no TRANSFER registered from it.
CREATE FUNCTION case_transfer_open(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM application a
                    WHERE a.id = p_application AND a.state NOT IN ('DRAFT', 'REFUSED', 'WITHDRAWN')
                      AND NOT EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status = 'REGISTERED'))
$$;

-- At submission, a procedure B case names its title: the titleNumber of its data, at the case's office (the parcel's),
-- resting on the case's parcel, active, and named by no other open procedure B case. Never changed afterwards.
CREATE FUNCTION application_title_resolve() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    t      land_title;
    state  jsonb;
BEGIN
    IF NEW.title_id IS DISTINCT FROM OLD.title_id AND NOT (OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED') THEN
        RAISE EXCEPTION 'the title of application % never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT (OLD.state = 'DRAFT' AND NEW.state = 'SUBMITTED') THEN
        RETURN NEW;
    END IF;
    IF (SELECT ty.requirements -> 'procedure' ->> 'registers' FROM application_type ty WHERE ty.id = NEW.type_version_id)
       IS DISTINCT FROM 'TRANSFER' THEN
        NEW.title_id := NULL;
        RETURN NEW;
    END IF;
    SELECT * INTO t FROM land_title WHERE number = btrim(NEW.data ->> 'titleNumber') AND office_id = NEW.office_id;
    IF t.id IS NULL THEN
        PERFORM procedure_refuse('TITLE_NOT_FOUND');
    END IF;
    -- Two submissions naming the same title are ordered here (a lock of its own, so no deposit waits on it).
    PERFORM pg_advisory_xact_lock(hashtextextended('snigf.title-case:' || t.id::text, 0));
    state := title_state(t.id, title_current_version(t.id));
    IF state ->> 'status' = 'CLOSED' THEN
        PERFORM procedure_refuse('TITLE_CLOSED');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM parcel_version pv WHERE pv.id = (state ->> 'parcelVersion')::uuid AND pv.parcel_id = NEW.parcel_id) THEN
        PERFORM procedure_refuse('TITLE_NOT_ON_PARCEL');
    END IF;
    IF EXISTS (SELECT 1 FROM application o WHERE o.title_id = t.id AND o.id <> NEW.id AND case_transfer_open(o.id)) THEN
        PERFORM procedure_refuse('TITLE_CASE_OPEN');
    END IF;
    NEW.title_id := t.id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_title_resolve BEFORE UPDATE ON application FOR EACH ROW EXECUTE FUNCTION application_title_resolve();

-- --- A deposit names a case ------------------------------------------------------------------------------------------------

-- One open deposit per case.
CREATE UNIQUE INDEX ux_pending_transaction_case_open ON pending_transaction (application_id)
    WHERE application_id IS NOT NULL AND status IN ('HOLDING', 'IN_CONFLICT');

-- The case a deposit on a title names, by its reference, or NULL when none is given; refuses one that cannot be named. The
-- title is locked by the caller; the case is locked here, only when it can take a deposit (so nothing waits in the other order).
CREATE FUNCTION deposit_case_resolve(p_title land_title, p_reference text) RETURNS uuid
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
BEGIN
    IF p_reference IS NULL OR btrim(p_reference) = '' THEN
        RETURN NULL;
    END IF;
    SELECT * INTO a FROM application WHERE reference = btrim(p_reference) AND office_id = p_title.office_id;
    IF a.id IS NULL THEN
        PERFORM register_refuse('CASE_NOT_FOUND');
    END IF;
    IF application_procedure(a.id) ->> 'registers' IS DISTINCT FROM 'TRANSFER' THEN
        PERFORM register_refuse('CASE_NOT_TRANSFER');
    END IF;
    IF a.title_id IS DISTINCT FROM p_title.id THEN
        PERFORM register_refuse('CASE_TITLE_MISMATCH');
    END IF;
    IF a.state NOT IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW') THEN
        PERFORM register_refuse('CASE_NOT_OPEN');
    END IF;
    SELECT * INTO a FROM application WHERE id = a.id FOR UPDATE;
    IF a.state NOT IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW') THEN
        PERFORM register_refuse('CASE_NOT_OPEN');
    END IF;
    IF EXISTS (SELECT 1 FROM pending_transaction t WHERE t.application_id = a.id AND t.status IN ('HOLDING', 'IN_CONFLICT')) THEN
        PERFORM register_refuse('CASE_DEPOSIT_OPEN');
    END IF;
    RETURN a.id;
END;
$$;

DROP FUNCTION transaction_deposit(uuid, text, uuid, uuid, text[], interval);
CREATE FUNCTION transaction_deposit(p_title uuid, p_kind text, p_depositor uuid, p_submitted_by uuid, p_evidence text[],
                                    p_review_after interval, p_case text DEFAULT NULL) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor   uuid := current_actor();
    title   land_title;
    holding uuid;
    new_id  uuid;
    v_case  uuid;
BEGIN
    SELECT * INTO title FROM land_title WHERE id = p_title FOR UPDATE;
    IF title.id IS NULL OR NOT holds_at(actor, 'register.read', title.office_id) THEN
        PERFORM register_refuse('TITLE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, 'register.propose', title.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF title_state(p_title, title_current_version(p_title))->>'status' = 'CLOSED' THEN
        PERFORM register_refuse('TITLE_CLOSED');
    END IF;
    IF p_review_after IS NULL OR p_review_after <= interval '0' THEN
        PERFORM register_refuse('REVIEW_PERIOD_INVALID');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = p_depositor AND rp.office_id = title.office_id AND rp.kind <> 'NOTARY') THEN
        PERFORM register_refuse('DEPOSITOR_INVALID');
    END IF;
    IF p_submitted_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = p_submitted_by AND rp.office_id = title.office_id AND rp.kind = 'NOTARY') THEN
        PERFORM register_refuse('NOTARY_INVALID');
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('SALE', 'GIFT', 'INHERITANCE') OR coalesce(cardinality(p_evidence), 0) = 0 THEN
        PERFORM register_refuse('DEPOSIT_INVALID');
    END IF;
    v_case := deposit_case_resolve(title, p_case);
    IF v_case IS NOT NULL AND p_kind <> 'SALE' THEN
        PERFORM register_refuse('CASE_NOT_TRANSFER');
    END IF;
    SELECT id INTO holding FROM pending_transaction WHERE title_id = p_title AND status = 'HOLDING';
    IF holding IS NULL THEN
        SELECT holding_id INTO holding FROM transaction_conflict WHERE title_id = p_title AND status = 'OPEN' ORDER BY opened_at LIMIT 1;
    END IF;
    INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, submitted_by_party_id, received_by,
                                     evidence_references, status, review_due_at, application_id)
    VALUES (p_title, title.office_id, p_kind, 'COUNTER', p_depositor, p_submitted_by, actor, p_evidence,
            CASE WHEN holding IS NULL THEN 'HOLDING' ELSE 'IN_CONFLICT' END, clock_timestamp() + p_review_after, v_case)
    RETURNING id INTO new_id;
    IF holding IS NOT NULL THEN
        INSERT INTO transaction_conflict (title_id, office_id, holding_id, arriving_id) VALUES (p_title, title.office_id, holding, new_id);
    END IF;
    RETURN new_id;
END;
$$;

DROP FUNCTION transaction_deposit_professional(uuid, text, jsonb, jsonb, interval);
CREATE FUNCTION transaction_deposit_professional(p_title uuid, p_kind text, p_depositor jsonb, p_documents jsonb, p_review_after interval,
                                                 p_case text DEFAULT NULL)
    RETURNS TABLE (transaction_id uuid, conflict_id uuid)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor      uuid := current_actor();
    title      land_title;
    profile    professional_profile;
    d_kind     text := btrim(p_depositor->>'kind');
    d_name     text := btrim(p_depositor->>'fullName');
    d_id_kind  text := btrim(p_depositor->>'identityKind');
    d_id_no    text := btrim(p_depositor->>'identityNumber');
    depositor  register_party;
    notary     uuid;
    party_name text;
    party_id_no text;
    holding    uuid;
    new_id     uuid;
    opened     uuid;
    doc        jsonb;
    v_case     uuid;
BEGIN
    SELECT * INTO title FROM land_title WHERE land_title.id = p_title FOR UPDATE;
    IF title.id IS NULL OR NOT holds_professional_at(actor, 'register.deposit.submit', title.office_id) THEN
        PERFORM register_refuse('TITLE_NOT_FOUND');
    END IF;
    IF title_state(p_title, title_current_version(p_title))->>'status' = 'CLOSED' THEN
        PERFORM register_refuse('TITLE_CLOSED');
    END IF;
    IF p_review_after IS NULL OR p_review_after <= interval '0' THEN
        PERFORM register_refuse('REVIEW_PERIOD_INVALID');
    END IF;
    IF p_kind IS NULL OR p_kind NOT IN ('SALE', 'GIFT', 'INHERITANCE') THEN
        PERFORM register_refuse('DEPOSIT_INVALID');
    END IF;
    IF d_kind IS NULL OR d_kind NOT IN ('NATURAL_PERSON', 'LEGAL_PERSON')
       OR d_name IS NULL OR length(d_name) NOT BETWEEN 3 AND 200 OR d_name NOT LIKE '%DEMO%'
       OR d_id_kind IS NULL OR d_id_kind !~ '^[A-Z_]{2,40}$'
       OR d_id_no IS NULL OR length(d_id_no) NOT BETWEEN 5 AND 64 OR d_id_no NOT LIKE '%DEMO%' THEN
        PERFORM register_refuse('DEPOSITOR_INVALID');
    END IF;
    IF p_documents IS NULL OR jsonb_typeof(p_documents) <> 'array' OR jsonb_array_length(p_documents) NOT BETWEEN 1 AND 20 THEN
        PERFORM register_refuse('DOCUMENTS_INVALID');
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_documents) x
                WHERE x->>'category' IS NULL OR x->>'category' NOT IN ('DEED', 'IDENTITY', 'AUTHORISATION', 'TAX_OR_DUTY', 'OTHER')) THEN
        PERFORM register_refuse('CATEGORY_INVALID');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(p_documents) x WHERE x->>'category' = 'DEED') THEN
        PERFORM register_refuse('DEED_REQUIRED');
    END IF;
    IF register_actor_interested(actor, p_title, NULL) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF EXISTS (SELECT 1 FROM pending_transaction t JOIN deposit_document d ON d.transaction_id = t.id
                WHERE t.title_id = p_title AND t.submitted_by_account = actor AND t.status IN ('HOLDING', 'IN_CONFLICT')
                  AND d.category = 'DEED'
                  AND d.sha256 IN (SELECT x->>'sha256' FROM jsonb_array_elements(p_documents) x WHERE x->>'category' = 'DEED')) THEN
        PERFORM register_refuse('DUPLICATE_DEPOSIT');
    END IF;
    v_case := deposit_case_resolve(title, p_case);
    IF v_case IS NOT NULL AND p_kind <> 'SALE' THEN
        PERFORM register_refuse('CASE_NOT_TRANSFER');
    END IF;
    -- A professional who is a person of the case never deposits for it.
    IF v_case IS NOT NULL AND register_case_handler(v_case, actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;

    SELECT * INTO depositor FROM register_party rp
     WHERE rp.office_id = title.office_id AND rp.kind = d_kind AND rp.full_name = d_name
       AND rp.identity_kind = d_id_kind AND rp.identity_number = d_id_no
     ORDER BY rp.created_at, rp.id LIMIT 1;
    IF depositor.id IS NULL THEN
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, created_by)
        VALUES (title.office_id, d_kind, d_name, d_id_kind, d_id_no, actor)
        RETURNING * INTO depositor;
    ELSIF depositor.account_id = actor THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;

    SELECT * INTO profile FROM professional_profile WHERE user_id = actor;
    SELECT rp.id INTO notary FROM register_party rp
     WHERE rp.office_id = title.office_id AND rp.kind = 'NOTARY' AND rp.account_id = actor
     ORDER BY rp.created_at DESC, rp.id DESC LIMIT 1;
    IF notary IS NULL THEN
        SELECT CASE WHEN u.display_name LIKE '%DEMO%' THEN btrim(u.display_name) ELSE left('DEMO ' || btrim(u.display_name), 200) END
          INTO party_name FROM app_user u WHERE u.id = actor;
        party_id_no := CASE WHEN length(profile.registration_number) >= 5 THEN profile.registration_number ELSE 'PRO-' || profile.registration_number END;
        IF party_name IS NULL OR length(party_name) < 3 OR length(party_id_no) > 64 THEN
            PERFORM register_refuse('NOTARY_PARTY_INVALID');
        END IF;
        INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, chamber, registration_number, jurisdiction,
                                    account_id, created_by)
        VALUES (title.office_id, 'NOTARY', party_name, 'PROFESSIONAL_REGISTRATION', party_id_no, profile.chamber, profile.registration_number,
                profile.jurisdiction, actor, actor)
        RETURNING id INTO notary;
    END IF;

    SELECT t.id INTO holding FROM pending_transaction t WHERE t.title_id = p_title AND t.status = 'HOLDING';
    IF holding IS NULL THEN
        SELECT c.holding_id INTO holding FROM transaction_conflict c WHERE c.title_id = p_title AND c.status = 'OPEN' ORDER BY c.opened_at LIMIT 1;
    END IF;
    INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, submitted_by_party_id, received_by,
                                     submitted_by_account, submitted_capacity, evidence_references, status, review_due_at, application_id)
    VALUES (p_title, title.office_id, p_kind, 'PROFESSIONAL_ACCOUNT', depositor.id, notary, NULL, actor, profile.profession,
            ARRAY(SELECT left((x->>'category') || ': ' || coalesce(x->>'name', ''), 200) FROM jsonb_array_elements(p_documents) x),
            CASE WHEN holding IS NULL THEN 'HOLDING' ELSE 'IN_CONFLICT' END, clock_timestamp() + p_review_after, v_case)
    RETURNING id INTO new_id;
    FOR doc IN SELECT x FROM jsonb_array_elements(p_documents) x LOOP
        INSERT INTO deposit_document (transaction_id, office_id, category, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, title.office_id, doc->>'category', doc->>'storageKey', doc->>'sha256', (doc->>'sizeBytes')::bigint, doc->>'mediaType',
                doc->>'name', actor);
    END LOOP;
    IF holding IS NOT NULL THEN
        INSERT INTO transaction_conflict (title_id, office_id, holding_id, arriving_id) VALUES (p_title, title.office_id, holding, new_id)
        RETURNING id INTO opened;
    END IF;
    RETURN QUERY SELECT new_id, opened;
END;
$$;

-- The case's open deposit (HOLDING or IN_CONFLICT), if any.
CREATE FUNCTION case_open_deposit(p_application uuid) RETURNS pending_transaction
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT * FROM pending_transaction WHERE application_id = p_application AND status IN ('HOLDING', 'IN_CONFLICT')
$$;

-- --- Procedure B's stages --------------------------------------------------------------------------------------------------

-- The title's holders at a version, each with the transferor of the case matched by identity (same kind and number as the
-- register party, from the transferor's latest identity), or NULL; and the transferors matched to no holder.
CREATE FUNCTION case_transferor_match(p_application uuid, p_title uuid, p_version int) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    WITH holders AS (
        SELECT DISTINCT (h ->> 'party')::uuid AS party FROM title_version v, jsonb_array_elements(v.holders) h
         WHERE v.title_id = p_title AND v.version_no = p_version),
    transferors AS (
        SELECT cp.id, cp.ref, i.identity_kind, i.identity_number FROM case_person cp
          JOIN case_role r ON r.person_id = cp.id AND r.role = 'TRANSFEROR'
          CROSS JOIN LATERAL case_identity_latest(cp.id) i
         WHERE cp.application_id = p_application),
    matched AS (
        SELECT h.party, rp.full_name,
               (SELECT array_agg(t.ref ORDER BY t.ref) FROM transferors t
                 WHERE t.identity_kind = rp.identity_kind AND t.identity_number = rp.identity_number) AS refs
          FROM holders h JOIN register_party rp ON rp.id = h.party)
    SELECT jsonb_build_object(
        'holders', (SELECT coalesce(jsonb_agg(jsonb_build_object('party', m.party, 'name', m.full_name,
                                                                 'transferor', CASE WHEN cardinality(m.refs) = 1 THEN m.refs[1] END)
                                              ORDER BY m.party), '[]') FROM matched m),
        'unmatchedTransferors', (SELECT coalesce(jsonb_agg(t.ref ORDER BY t.ref), '[]') FROM transferors t
                                  WHERE NOT EXISTS (SELECT 1 FROM matched m WHERE t.ref = ANY (m.refs))),
        'exact', NOT EXISTS (SELECT 1 FROM matched m WHERE coalesce(cardinality(m.refs), 0) <> 1)
                 AND NOT EXISTS (SELECT 1 FROM transferors t WHERE NOT EXISTS (SELECT 1 FROM matched m WHERE t.ref = ANY (m.refs)))
                 AND (SELECT count(*) FROM matched) = (SELECT count(*) FROM transferors)
                 AND EXISTS (SELECT 1 FROM holders))
$$;

-- The holders of a title version as rights (party, interest, share), to compare two versions (D47).
CREATE FUNCTION title_holder_rights(p_title uuid, p_version int) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce((SELECT jsonb_agg(jsonb_build_object('party', h ->> 'party', 'interest', h ->> 'interest', 'shareNum', h -> 'shareNum',
                                                         'shareDen', h -> 'shareDen') ORDER BY h ->> 'party', h ->> 'interest')
                       FROM title_version v, jsonb_array_elements(v.holders) h WHERE v.title_id = p_title AND v.version_no = p_version), '[]')
$$;

CREATE FUNCTION title_blocked(p_title uuid, p_version int) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM title_version v, jsonb_array_elements(v.restrictions) r
                    WHERE v.title_id = p_title AND v.version_no = p_version AND (r ->> 'blocksTransfer')::boolean)
$$;

CREATE OR REPLACE FUNCTION procedure_stage_problems(p_application uuid, p_stage text) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    latest    procedure_stage_record := procedure_stage_latest(p_application, p_stage);
    visit     procedure_stage_record;
    notice    procedure_notice;
    current_v int;
    title     uuid;
    problems  text[] := ARRAY[]::text[];
BEGIN
    IF latest.id IS NULL THEN
        RETURN ARRAY['STAGE_MISSING:' || p_stage];
    END IF;
    IF latest.outcome <> 'COMPLETED' THEN
        RETURN ARRAY['STAGE_NOT_SATISFIED:' || p_stage];
    END IF;
    IF p_stage = 'COMMISSION_REPORT' THEN
        visit := procedure_stage_latest(p_application, 'FIELD_VISIT');
        IF visit.id IS NULL OR (latest.details ->> 'fieldVisitRecord')::uuid IS DISTINCT FROM visit.id THEN
            problems := problems || 'REPORT_ON_SUPERSEDED_VISIT'::text;
        END IF;
    ELSIF p_stage = 'CADASTRAL_REVIEW' THEN
        SELECT p.current_version_no INTO current_v FROM application a JOIN parcel p ON p.id = a.parcel_id WHERE a.id = p_application;
        IF (latest.details ->> 'parcelVersionNo')::int IS DISTINCT FROM current_v THEN
            problems := problems || 'PARCEL_CHANGED_SINCE_REVIEW'::text;
        END IF;
    ELSIF p_stage = 'TITLE_VERIFICATION'
          AND NOT EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = p_application AND i.status = 'REGISTERED') THEN
        -- D47: the verification holds while the holders are those verified and nothing blocks; once the case's TRANSFER is
        -- registered, the holders are the new ones and nothing is checked any more.
        title := (latest.details ->> 'titleId')::uuid;
        current_v := title_current_version(title);
        IF current_v IS DISTINCT FROM (latest.details ->> 'versionNo')::int
           AND title_holder_rights(title, current_v) IS DISTINCT FROM title_holder_rights(title, (latest.details ->> 'versionNo')::int) THEN
            problems := problems || 'TITLE_CHANGED_SINCE_VERIFICATION'::text;
        END IF;
        IF title_blocked(title, current_v) THEN
            problems := problems || 'BLOCKING_RESTRICTION'::text;
        END IF;
        IF EXISTS (SELECT 1 FROM case_person_identity i JOIN case_role r ON r.person_id = i.person_id AND r.role = 'TRANSFEROR'
                    WHERE i.application_id = p_application AND i.entered_at > latest.recorded_at) THEN
            problems := problems || 'TRANSFERORS_CHANGED_SINCE_VERIFICATION'::text;
        END IF;
    ELSIF p_stage = 'DUTIES_EVIDENCE' THEN
        IF NOT EXISTS (SELECT 1 FROM pending_transaction t WHERE t.id = (latest.details ->> 'depositId')::uuid
                         AND t.status IN ('HOLDING', 'REGISTERED')) THEN
            problems := problems || 'DEPOSIT_CHANGED_SINCE_EVIDENCE'::text;
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM procedure_objection_decision d JOIN procedure_objection o ON o.id = d.objection_id
                WHERE o.application_id = p_application AND d.outcome = 'UPHELD' AND p_stage = ANY (d.review_stages)
                  AND d.decided_at > latest.recorded_at) THEN
        problems := problems || ('OBJECTION_REVIEW_PENDING:' || p_stage);
    END IF;
    IF EXISTS (SELECT 1 FROM case_reconsideration r WHERE r.application_id = p_application AND p_stage = ANY (r.stages)
                AND r.opened_at > latest.recorded_at) THEN
        problems := problems || ('RECONSIDERATION_PENDING:' || p_stage);
    END IF;
    IF p_stage = 'PUBLICATION' THEN
        IF EXISTS (SELECT 1 FROM jsonb_array_elements_text(coalesce(application_procedure(p_application) -> 'stages', '[]')) s
                     JOIN procedure_stage ps ON ps.code = s.value
                    WHERE ps.rank < (SELECT rank FROM procedure_stage WHERE code = 'PUBLICATION')
                      AND (procedure_stage_latest(p_application, s.value)).recorded_at > latest.recorded_at) THEN
            problems := problems || 'PUBLISHED_BEFORE_CHANGE'::text;
        END IF;
        SELECT * INTO notice FROM procedure_notice WHERE record_id = latest.id;
        IF notice.id IS NULL OR notice.closes_at > now() THEN
            problems := problems || 'OBJECTION_PERIOD_OPEN'::text;
        END IF;
    END IF;
    RETURN problems;
END;
$$;

CREATE OR REPLACE FUNCTION procedure_stage_record_add(p_application uuid, p_stage text, p_outcome text, p_finding text, p_details jsonb,
                                                      p_evidence uuid[], p_supersedes uuid, p_correction_reason text, p_files jsonb)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    proc      jsonb;
    catalogue procedure_stage;
    latest    procedure_stage_record;
    visit     procedure_stage_record;
    given     jsonb := coalesce(p_details, '{}'::jsonb);
    details   jsonb;
    evidence  uuid[] := coalesce(p_evidence, '{}');
    files     jsonb := coalesce(p_files, '[]'::jsonb);
    f         jsonb;
    visited   date;
    reported  date;
    period    interval;
    opened    timestamptz;
    replaced  uuid;
    new_id    uuid;
    t         land_title;
    v_version int;
    matching  jsonb;
    deposit   pending_transaction;
    receipt   deposit_document;
BEGIN
    SELECT * INTO catalogue FROM procedure_stage WHERE code = p_stage;
    IF catalogue.code IS NULL THEN
        PERFORM procedure_refuse('STAGE_UNKNOWN');
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        PERFORM procedure_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, catalogue.permission, a.office_id) THEN
        PERFORM procedure_refuse('PROCEDURE_FORBIDDEN');
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    proc := application_procedure(a.id);
    IF proc IS NULL OR NOT (proc -> 'stages') ? p_stage THEN
        PERFORM procedure_refuse('STAGE_NOT_IN_PROCEDURE');
    END IF;
    IF a.state <> 'UNDER_REVIEW' THEN
        PERFORM procedure_refuse('CASE_NOT_UNDER_REVIEW');
    END IF;
    IF actor = a.applicant_id THEN
        PERFORM procedure_refuse('APPLICANT_CANNOT_RECORD');
    END IF;
    IF case_ever_assigned(a.id, actor) THEN
        PERFORM procedure_refuse('ASSIGNEE_CANNOT_RECORD');
    END IF;
    -- A person of the case never records one of its stages (a conservator who is a transferor, say).
    IF EXISTS (SELECT 1 FROM case_person cp WHERE cp.application_id = a.id AND cp.account_id = actor) THEN
        PERFORM procedure_refuse('APPLICANT_CANNOT_RECORD');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('COMPLETED', 'NOT_SATISFIED') THEN
        PERFORM procedure_refuse('OUTCOME_REQUIRED');
    END IF;
    IF NOT procedure_text_ok(p_finding, 10, 4000) THEN
        PERFORM procedure_refuse('FINDING_REQUIRED');
    END IF;

    latest := procedure_stage_latest(a.id, p_stage);
    IF latest.id IS NULL AND p_supersedes IS NOT NULL THEN
        PERFORM procedure_refuse('NOTHING_TO_CORRECT');
    END IF;
    IF latest.id IS NOT NULL AND p_supersedes IS NULL THEN
        PERFORM procedure_refuse('STAGE_ALREADY_RECORDED');
    END IF;
    IF latest.id IS NOT NULL AND p_supersedes IS DISTINCT FROM latest.id THEN
        PERFORM procedure_refuse('STALE_RECORD');
    END IF;
    IF p_supersedes IS NOT NULL AND NOT procedure_text_ok(p_correction_reason, 10, 2000) THEN
        PERFORM procedure_refuse('CORRECTION_REASON_REQUIRED');
    END IF;

    IF cardinality(evidence) > 20 OR EXISTS (SELECT 1 FROM unnest(evidence) v
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv JOIN document d ON d.id = dv.document_id
                                   WHERE dv.id = v AND d.application_id = a.id AND dv.status = 'STORED')) THEN
        PERFORM procedure_refuse('EVIDENCE_NOT_OF_CASE');
    END IF;
    IF jsonb_typeof(files) <> 'array' OR jsonb_array_length(files) > 5 THEN
        PERFORM procedure_refuse('TOO_MANY_FILES');
    END IF;

    IF jsonb_typeof(given) <> 'object' THEN
        PERFORM procedure_refuse('DETAILS_INVALID');
    END IF;
    CASE p_stage
    WHEN 'CADASTRAL_REVIEW' THEN
        IF given <> '{}'::jsonb THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        IF p_outcome = 'COMPLETED' AND application_open_alerts(a.id) > 0 THEN
            PERFORM procedure_refuse('OPEN_ALERT');
        END IF;
        SELECT jsonb_build_object('parcelId', p.id, 'parcelReference', p.reference, 'parcelVersionNo', p.current_version_no,
                                  'openAlerts', application_open_alerts(a.id))
          INTO details FROM parcel p WHERE p.id = a.parcel_id;
    WHEN 'FIELD_VISIT' THEN
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k NOT IN ('visitedOn', 'members')) THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        visited := procedure_date(given ->> 'visitedOn');
        IF visited IS NULL OR visited > current_date OR visited < a.submitted_at::date THEN
            PERFORM procedure_refuse('VISIT_DATE_INVALID');
        END IF;
        IF NOT procedure_people_ok(given -> 'members', ARRAY['name', 'capacity']) THEN
            PERFORM procedure_refuse('MEMBERS_INVALID');
        END IF;
        details := jsonb_build_object('visitedOn', visited::text, 'members', given -> 'members');
    WHEN 'COMMISSION_REPORT' THEN
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k NOT IN ('reportReference', 'reportDate', 'signatories')) THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        visit := procedure_stage_latest(a.id, 'FIELD_VISIT');
        IF visit.id IS NULL OR visit.outcome <> 'COMPLETED' THEN
            PERFORM procedure_refuse('FIELD_VISIT_REQUIRED');
        END IF;
        IF visit.recorded_by = actor THEN
            PERFORM procedure_refuse('VISIT_RECORDER_CANNOT_REPORT');
        END IF;
        IF NOT procedure_text_ok(given ->> 'reportReference', 3, 60) THEN
            PERFORM procedure_refuse('REPORT_REFERENCE_REQUIRED');
        END IF;
        reported := procedure_date(given ->> 'reportDate');
        IF reported IS NULL OR reported > current_date OR reported < (visit.details ->> 'visitedOn')::date THEN
            PERFORM procedure_refuse('REPORT_DATE_INVALID');
        END IF;
        IF NOT procedure_people_ok(given -> 'signatories', ARRAY['name', 'capacity']) THEN
            PERFORM procedure_refuse('SIGNATORIES_INVALID');
        END IF;
        IF cardinality(evidence) = 0 AND jsonb_array_length(files) = 0 THEN
            PERFORM procedure_refuse('REPORT_EVIDENCE_REQUIRED');
        END IF;
        details := jsonb_build_object('reportReference', btrim(given ->> 'reportReference'), 'reportDate', reported::text,
                                      'signatories', given -> 'signatories', 'fieldVisitRecord', visit.id);
    WHEN 'PUBLICATION' THEN
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k <> 'objectionPeriod') THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        IF p_outcome <> 'COMPLETED' THEN
            PERFORM procedure_refuse('OUTCOME_INVALID');
        END IF;
        BEGIN
            period := (given ->> 'objectionPeriod')::interval;
        EXCEPTION WHEN others THEN
            period := NULL;
        END;
        IF period IS NULL OR period < interval '1 minute' OR period > interval '365 days' THEN
            PERFORM procedure_refuse('PERIOD_INVALID');
        END IF;
        IF NOT procedure_earlier_stages_complete(a.id, 'PUBLICATION') THEN
            PERFORM procedure_refuse('EARLIER_STAGES_INCOMPLETE');
        END IF;
        SELECT jsonb_build_object('objectionPeriod', period::text, 'parcelReference', p.reference, 'locality', pv.locality)
          INTO details
          FROM parcel p JOIN parcel_version pv ON pv.parcel_id = p.id AND pv.version_no = p.current_version_no
         WHERE p.id = a.parcel_id;
        IF details IS NULL THEN
            PERFORM procedure_refuse('CASE_PARCEL_MISSING');
        END IF;
    WHEN 'TITLE_VERIFICATION' THEN
        -- The conservator says what they read (the finding); the title, its version and the matching are the database's.
        IF given <> '{}'::jsonb THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        SELECT * INTO t FROM land_title WHERE id = a.title_id;
        IF t.id IS NULL THEN
            PERFORM procedure_refuse('CASE_TITLE_MISSING');
        END IF;
        IF register_actor_interested(actor, t.id, NULL) THEN
            PERFORM procedure_refuse('ACTOR_HAS_INTEREST');
        END IF;
        v_version := title_current_version(t.id);
        matching := case_transferor_match(a.id, t.id, v_version);
        deposit := case_open_deposit(a.id);
        IF p_outcome = 'COMPLETED' THEN
            IF title_state(t.id, v_version) ->> 'status' = 'CLOSED' THEN
                PERFORM procedure_refuse('TITLE_CLOSED');
            END IF;
            IF NOT (matching ->> 'exact')::boolean THEN
                PERFORM procedure_refuse('TRANSFERORS_NOT_HOLDERS');
            END IF;
            IF EXISTS (SELECT 1 FROM case_person cp JOIN case_role r ON r.person_id = cp.id AND r.role = 'TRANSFEROR'
                         CROSS JOIN LATERAL case_identity_latest(cp.id) i
                        WHERE cp.application_id = a.id AND (i.source <> 'RECORDED' OR case_check_of('IDENTITY', i.id) IS DISTINCT FROM 'MATCHES')) THEN
                PERFORM procedure_refuse('TRANSFEROR_NOT_CHECKED');
            END IF;
            IF title_blocked(t.id, v_version) THEN
                PERFORM procedure_refuse('BLOCKING_RESTRICTION');
            END IF;
            IF EXISTS (SELECT 1 FROM transaction_conflict c WHERE c.title_id = t.id AND c.status = 'OPEN') THEN
                PERFORM procedure_refuse('OPEN_CONFLICT');
            END IF;
            IF deposit.id IS NULL OR deposit.status <> 'HOLDING' THEN
                PERFORM procedure_refuse('DEPOSIT_NOT_HOLDING');
            END IF;
        END IF;
        details := jsonb_build_object('titleId', t.id, 'titleNumber', t.number, 'versionNo', v_version,
                                      'holders', matching -> 'holders', 'unmatchedTransferors', matching -> 'unmatchedTransferors',
                                      'depositId', deposit.id, 'depositStatus', deposit.status);
    WHEN 'DUTIES_EVIDENCE' THEN
        -- D24: the receipt deposited with the deed, checked; never an amount computed by SNIGF.
        IF EXISTS (SELECT 1 FROM jsonb_object_keys(given) k WHERE k NOT IN ('receiptDocument', 'receiptReference')) THEN
            PERFORM procedure_refuse('DETAILS_INVALID');
        END IF;
        deposit := case_open_deposit(a.id);
        IF given ? 'receiptDocument' THEN
            IF coalesce(given ->> 'receiptDocument', '') !~ '^[0-9a-f-]{36}$' THEN
                PERFORM procedure_refuse('DUTY_RECEIPT_REQUIRED');
            END IF;
            SELECT * INTO receipt FROM deposit_document d
             WHERE d.id = (given ->> 'receiptDocument')::uuid AND d.transaction_id = deposit.id AND d.category = 'TAX_OR_DUTY';
        END IF;
        IF p_outcome = 'COMPLETED' THEN
            IF deposit.id IS NULL OR deposit.status <> 'HOLDING' THEN
                PERFORM procedure_refuse('DEPOSIT_NOT_HOLDING');
            END IF;
            IF receipt.id IS NULL THEN
                PERFORM procedure_refuse('DUTY_RECEIPT_REQUIRED');
            END IF;
            IF NOT procedure_text_ok(given ->> 'receiptReference', 3, 60) THEN
                PERFORM procedure_refuse('RECEIPT_REFERENCE_REQUIRED');
            END IF;
        ELSIF given ? 'receiptDocument' AND receipt.id IS NULL THEN
            PERFORM procedure_refuse('DUTY_RECEIPT_REQUIRED');
        END IF;
        details := jsonb_strip_nulls(jsonb_build_object('depositId', deposit.id, 'receiptDocument', receipt.id, 'receiptSha256', receipt.sha256,
                                                        'receiptReference', nullif(btrim(coalesce(given ->> 'receiptReference', '')), '')));
    END CASE;

    INSERT INTO procedure_stage_record (application_id, office_id, stage, outcome, finding, details, evidence_versions,
                                        supersedes_id, correction_reason, recorded_by)
    VALUES (a.id, a.office_id, p_stage, p_outcome, btrim(p_finding), details, evidence, p_supersedes,
            CASE WHEN p_supersedes IS NULL THEN NULL ELSE btrim(p_correction_reason) END, actor)
    RETURNING id, recorded_at INTO new_id, opened;
    FOR f IN SELECT value FROM jsonb_array_elements(files) LOOP
        INSERT INTO procedure_stage_file (record_id, storage_key, sha256, size_bytes, detected_type, original_name, uploaded_by)
        VALUES (new_id, f ->> 'storageKey', f ->> 'sha256', (f ->> 'size')::bigint, f ->> 'type', f ->> 'name', actor);
    END LOOP;
    IF p_stage = 'PUBLICATION' THEN
        SELECT n.id INTO replaced FROM procedure_notice n WHERE n.record_id = p_supersedes;
        INSERT INTO procedure_notice (number, application_id, record_id, office_id, parcel_reference, locality, period,
                                      opens_at, closes_at, replaces_id)
        VALUES ('DEMO-PUB-' || to_char(opened AT TIME ZONE 'Africa/Douala', 'YYYY') || '-' || lpad(nextval('procedure_notice_seq')::text, 6, '0'),
                a.id, new_id, a.office_id, details ->> 'parcelReference', details ->> 'locality', period, opened, opened + period, replaced);
    END IF;
    RETURN new_id;
END;
$$;

CREATE OR REPLACE FUNCTION procedure_may_record(p_application uuid, p_stage text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM application a JOIN procedure_stage ps ON ps.code = p_stage
         WHERE a.id = p_application AND a.state = 'UNDER_REVIEW'
           AND (application_procedure(a.id) -> 'stages') ? p_stage
           AND holds_at(current_actor(), ps.permission, a.office_id)
           AND a.applicant_id <> current_actor()
           AND NOT case_ever_assigned(a.id, current_actor())
           AND NOT EXISTS (SELECT 1 FROM case_person cp WHERE cp.application_id = a.id AND cp.account_id = current_actor())
           AND (p_stage <> 'COMMISSION_REPORT' OR EXISTS (
                SELECT 1 FROM procedure_stage_latest(a.id, 'FIELD_VISIT') v
                 WHERE v.id IS NOT NULL AND v.outcome = 'COMPLETED' AND v.recorded_by <> current_actor()))
           AND (p_stage <> 'PUBLICATION' OR procedure_earlier_stages_complete(a.id, 'PUBLICATION')))
$$;

-- --- The TRANSFER from a case -----------------------------------------------------------------------------------------------

ALTER TABLE inscription DROP CONSTRAINT ck_inscription_case;
ALTER TABLE inscription ADD CONSTRAINT ck_inscription_case CHECK (application_id IS NULL OR kind IN ('CREATION', 'TRANSFER'));

-- Whoever handled the case at any step, persons included, and the conservator who received its counter deposit (D46).
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
        OR EXISTS (SELECT 1 FROM case_party_check c WHERE c.application_id = p_application AND c.checked_by = p_actor)
        OR EXISTS (SELECT 1 FROM pending_transaction t WHERE t.application_id = p_application AND t.received_by = p_actor))
$$;

-- A proposal from a case no longer is what was approved. The parcel is a CREATION's only: a TRANSFER rests on its title.
CREATE OR REPLACE FUNCTION register_case_proposal_stale(p_inscription uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM inscription i
         WHERE i.id = p_inscription AND i.application_id IS NOT NULL
           AND (i.decision_id IS DISTINCT FROM (case_decision_current(i.application_id)).id
                OR i.ownership_statement_id IS DISTINCT FROM (case_decision_current(i.application_id)).ownership_statement_id
                OR i.ownership_statement_id IS DISTINCT FROM (case_statement_latest(i.application_id)).id
                OR (i.kind = 'CREATION'
                    AND i.parcel_version_id IS DISTINCT FROM (SELECT v.id FROM application a JOIN parcel p ON p.id = a.parcel_id
                                                               JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                                                              WHERE a.id = i.application_id))
                OR (i.kind = 'TRANSFER'
                    AND (i.title_id IS DISTINCT FROM (SELECT a.title_id FROM application a WHERE a.id = i.application_id)
                         OR i.completes_transaction_id IS DISTINCT FROM (case_open_deposit(i.application_id)).id))
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                             JOIN case_person_identity src ON src.id = rp.source_identity_id
                            WHERE h.inscription_id = i.id
                              AND (src.id IS DISTINCT FROM (case_identity_latest(src.person_id)).id
                                   OR case_check_of('IDENTITY', src.id) IS DISTINCT FROM 'MATCHES'))
                OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                            WHERE h.inscription_id = i.id AND rp.source_identity_id IS NULL)))
$$;

-- What stops a TRANSFER from the case now, as reason codes, in the order they are reported. p_except is the TRANSFER being
-- signed, which is not "another one in progress".
CREATE FUNCTION register_transfer_case_problems(p_application uuid, p_except uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a         application;
    d         decision;
    proc      jsonb;
    deposit   pending_transaction;
    v_version int;
    code      text;
    problems  text[] := ARRAY[]::text[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN ARRAY['CASE_NOT_FOUND'];
    END IF;
    proc := application_procedure(a.id);
    IF proc IS NULL OR proc ->> 'registers' IS DISTINCT FROM 'TRANSFER' THEN
        RETURN ARRAY['PROCEDURE_NOT_TRANSFER'];
    END IF;
    IF a.title_id IS NULL THEN
        RETURN ARRAY['CASE_TITLE_MISSING'];
    END IF;
    IF p_except IS NOT NULL AND register_case_proposal_stale(p_except) THEN
        RETURN ARRAY['STALE_PROPOSAL'];
    END IF;
    problems := problems || procedure_registration_problems(a.id);
    IF a.state = 'APPROVED' THEN
        -- The gate again: the verification and the receipt still hold (D47); a precise code where there is one.
        FOR code IN SELECT g.code FROM unnest(procedure_gate_problems(a.id)) g(code)
                     WHERE g.code <> 'OBJECTION_OPEN' AND g.code NOT LIKE 'OBJECTION_REVIEW_PENDING:%' LOOP
            IF code NOT IN ('TITLE_CHANGED_SINCE_VERIFICATION', 'TRANSFERORS_CHANGED_SINCE_VERIFICATION', 'DEPOSIT_CHANGED_SINCE_EVIDENCE',
                            'BLOCKING_RESTRICTION') THEN
                code := 'PROCEDURE_INCOMPLETE';
            END IF;
            IF NOT code = ANY (problems) THEN
                problems := problems || code;
            END IF;
        END LOOP;
        d := case_decision_current(a.id);
        IF cardinality(case_party_problems(a.id)) > 0 OR d.ownership_statement_id IS NULL THEN
            problems := problems || 'PARTIES_INCOMPLETE'::text;
        ELSIF d.ownership_statement_id IS DISTINCT FROM (case_statement_latest(a.id)).id
              OR EXISTS (SELECT 1 FROM case_person_identity i WHERE i.application_id = a.id AND i.entered_at > d.at)
              OR EXISTS (SELECT 1 FROM case_mandate m WHERE m.application_id = a.id AND m.entered_at > d.at) THEN
            problems := problems || 'PARTIES_CHANGED_SINCE_DECISION'::text;
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status = 'REGISTERED') THEN
        RETURN problems || 'CASE_ALREADY_REGISTERED'::text;
    ELSIF EXISTS (SELECT 1 FROM inscription i WHERE i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
                    AND i.id IS DISTINCT FROM p_except) THEN
        problems := problems || 'TRANSFER_ALREADY_PROPOSED'::text;
    END IF;
    v_version := title_current_version(a.title_id);
    IF title_state(a.title_id, v_version) ->> 'status' = 'CLOSED' THEN
        problems := problems || 'TITLE_CLOSED'::text;
    END IF;
    deposit := case_open_deposit(a.id);
    IF deposit.id IS NULL OR deposit.status <> 'HOLDING' THEN
        problems := problems || 'DEPOSIT_NOT_HOLDING'::text;
    END IF;
    IF EXISTS (SELECT 1 FROM transaction_conflict c WHERE c.title_id = a.title_id AND c.status = 'OPEN') THEN
        problems := problems || 'OPEN_CONFLICT'::text;
    END IF;
    IF title_blocked(a.title_id, v_version) AND NOT 'BLOCKING_RESTRICTION' = ANY (problems) THEN
        problems := problems || 'BLOCKING_RESTRICTION'::text;
    END IF;
    IF EXISTS (SELECT 1 FROM deposit_document_replacement r WHERE r.transaction_id = deposit.id AND r.status = 'PENDING') THEN
        problems := problems || 'REPLACEMENT_PENDING'::text;
    END IF;
    RETURN problems;
END;
$$;

-- What stops the registration of an inscription from a case, by its kind.
CREATE FUNCTION register_inscription_case_problems(p_inscription uuid) RETURNS text[]
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE i.kind WHEN 'TRANSFER' THEN register_transfer_case_problems(i.application_id, i.id)
                       ELSE register_case_problems(i.application_id, i.id) END
      FROM inscription i WHERE i.id = p_inscription AND i.application_id IS NOT NULL
$$;

-- A TRANSFER completes a deposit that names a case only as that case's TRANSFER (ADR-040's plain path never does), and a
-- TRANSFER from a case completes that case's deposit on its title.
CREATE FUNCTION inscription_case_transfer_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    deposit_case uuid;
BEGIN
    IF NEW.kind <> 'TRANSFER' THEN
        RETURN NEW;
    END IF;
    SELECT t.application_id INTO deposit_case FROM pending_transaction t WHERE t.id = NEW.completes_transaction_id;
    IF deposit_case IS NOT NULL AND NEW.application_id IS NULL THEN
        PERFORM register_refuse('DEPOSIT_OF_A_CASE');
    END IF;
    IF NEW.application_id IS NOT NULL AND (deposit_case IS DISTINCT FROM NEW.application_id
            OR NEW.title_id IS DISTINCT FROM (SELECT a.title_id FROM application a WHERE a.id = NEW.application_id)) THEN
        PERFORM register_refuse('CASE_DEPOSIT_MISMATCH');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inscription_case_transfer_guard BEFORE INSERT ON inscription FOR EACH ROW EXECUTE FUNCTION inscription_case_transfer_guard();

-- The proposal: onto the case's title at its current version, completing its holding deposit; the holders are the decided
-- statement's transferees, each a party of the office from their latest checked identity. The conservator enters no holder.
CREATE FUNCTION register_propose_transfer_from_case(p_application uuid, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    d         decision;
    deposit   pending_transaction;
    problems  text[];
    s         record;
    party     uuid;
    new_id    uuid;
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL OR NOT holds_at(actor, 'register.read', a.office_id) THEN
        PERFORM register_refuse('CASE_NOT_FOUND');
    END IF;
    IF NOT holds_at(actor, 'register.propose', a.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    -- The title first, then the case: the order of every deposit and registration.
    IF a.title_id IS NOT NULL THEN
        PERFORM 1 FROM land_title WHERE id = a.title_id FOR UPDATE;
    END IF;
    SELECT * INTO a FROM application WHERE id = p_application FOR UPDATE;
    IF register_case_handler(a.id, actor) THEN
        PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
    END IF;
    problems := register_transfer_case_problems(a.id, NULL);
    IF cardinality(problems) > 0 THEN
        PERFORM register_refuse(problems[1]);
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    d := case_decision_current(a.id);
    deposit := case_open_deposit(a.id);
    IF EXISTS (SELECT 1 FROM unnest(d.cited_versions) e(v)
                WHERE NOT EXISTS (SELECT 1 FROM document_version dv WHERE dv.id = e.v AND dv.status = 'STORED')) THEN
        PERFORM register_refuse('EVIDENCE_NOT_FOUND');
    END IF;
    INSERT INTO inscription (title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
                             evidence_references, evidence_versions, completes_transaction_id, proposed_by, application_id, decision_id,
                             ownership_statement_id, ownership_statement_version)
    VALUES (a.title_id, a.office_id, 'TRANSFER', title_current_version(a.title_id), 'DECISION', a.reference, btrim(p_reason),
            ARRAY[a.reference, 'DECISION:' || d.id::text, 'OWNERSHIP_STATEMENT:' || d.ownership_statement_id::text,
                  'DEPOSIT:' || deposit.id::text], d.cited_versions, deposit.id, actor, a.id, d.id, d.ownership_statement_id,
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
    IF register_actor_interested(actor, a.title_id, new_id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN new_id;
END;
$$;

-- Two signatures for a TRANSFER from a case too (D46).
CREATE OR REPLACE FUNCTION inscription_dual_control(p_kind text, p_application uuid) RETURNS boolean
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_kind IN ('CLOSURE', 'CANCELLATION', 'CORRECTION') OR (p_kind IN ('CREATION', 'TRANSFER') AND p_application IS NOT NULL)
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
        PERFORM 1 FROM application WHERE id = i.application_id FOR UPDATE;
        IF register_case_handler(i.application_id, actor) THEN
            PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
        END IF;
        problems := register_inscription_case_problems(i.id);
        IF cardinality(problems) > 0 THEN
            PERFORM register_refuse(problems[1]);
        END IF;
    END IF;
    PERFORM register_store_signature(p_inscription, 'PROPOSED', actor, p_canonical, p_signature);
    UPDATE inscription SET status = 'CONFIRMATION_PENDING' WHERE id = p_inscription;
END;
$$;

-- Registration: an inscription from a case, CREATION or TRANSFER, is checked again under the case's lock when it is signed.
CREATE OR REPLACE FUNCTION register_apply(p_inscription uuid, p_actor uuid) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    i        inscription;
    title    uuid;
    state    jsonb;
    previous bytea;
    chain    bytea;
    s        record;
    next_no  int;
    problems text[];
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription;
    title := coalesce(i.title_id, i.new_title_id);
    next_no := i.expected_version + 1;
    IF i.kind = 'TRANSFER' THEN
        IF NOT EXISTS (SELECT 1 FROM pending_transaction t WHERE t.id = i.completes_transaction_id AND t.title_id = title AND t.status = 'HOLDING') THEN
            PERFORM register_refuse('TRANSACTION_NOT_HOLDING');
        END IF;
        IF EXISTS (SELECT 1 FROM transaction_conflict c WHERE c.title_id = title AND c.status = 'OPEN') THEN
            PERFORM register_refuse('OPEN_CONFLICT');
        END IF;
        IF EXISTS (SELECT 1 FROM title_version v, jsonb_array_elements(v.restrictions) r
                    WHERE v.title_id = title AND v.version_no = i.expected_version AND (r->>'blocksTransfer')::boolean) THEN
            PERFORM register_refuse('BLOCKING_RESTRICTION');
        END IF;
        IF EXISTS (SELECT 1 FROM deposit_document_replacement r WHERE r.transaction_id = i.completes_transaction_id AND r.status = 'PENDING') THEN
            PERFORM register_refuse('REPLACEMENT_PENDING');
        END IF;
    END IF;
    IF i.application_id IS NOT NULL THEN
        PERFORM 1 FROM application WHERE id = i.application_id FOR UPDATE;
        IF register_case_handler(i.application_id, p_actor) THEN
            PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
        END IF;
        problems := register_inscription_case_problems(i.id);
        IF cardinality(problems) > 0 THEN
            PERFORM register_refuse(problems[1]);
        END IF;
        IF i.kind = 'CREATION' AND NOT EXISTS (SELECT 1 FROM application a JOIN parcel p ON p.id = a.parcel_id
                         JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
                        WHERE a.id = i.application_id AND v.id = i.parcel_version_id) THEN
            PERFORM register_refuse('PARCEL_CHANGED');
        END IF;
    END IF;
    IF i.kind = 'CLOSURE' AND EXISTS (SELECT 1 FROM pending_transaction t WHERE t.title_id = title AND t.status IN ('HOLDING', 'IN_CONFLICT')) THEN
        PERFORM register_refuse('TITLE_LOCKED');
    END IF;
    SELECT p.hash INTO previous FROM inscription p WHERE p.title_id = title AND p.status = 'REGISTERED' AND p.number = i.expected_version;
    chain := coalesce(previous, ''::bytea) || inscription_canonical(i.id);
    FOR s IN SELECT officer_signature, server_signature FROM inscription_signature WHERE inscription_id = i.id ORDER BY act LOOP
        chain := chain || s.officer_signature || convert_to(s.server_signature, 'UTF8');
    END LOOP;
    IF i.kind = 'CREATION' THEN
        INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id, application_id)
        SELECT title,
               format('DEMO-TF-%s-%s-%s', o.code, lpad(q.n::text, 6, '0'), substr('ABCDEFGHJKMNPQRSTVWXYZ', 1 + (q.n % 22)::int, 1)),
               i.office_id, CASE WHEN i.application_id IS NULL THEN 'SYNTHETIC_DEMO' ELSE 'IMMATRICULATION' END, i.id, i.application_id
          FROM office o, (SELECT nextval('land_title_seq') AS n) q WHERE o.id = i.office_id;
    END IF;
    UPDATE inscription SET title_id = title, number = next_no, status = 'REGISTERED', registered_by = p_actor, registered_at = clock_timestamp(),
                           previous_hash = previous, hash = sha256(chain),
                           confirmed_by = CASE WHEN status = 'CONFIRMATION_PENDING' THEN p_actor END
     WHERE id = i.id;
    state := title_state(title, next_no);
    INSERT INTO title_version (title_id, version_no, inscription_id, status, area_m2, parcel_version_id, holders, charges, restrictions)
    VALUES (title, next_no, i.id, state->>'status', (state->>'areaM2')::numeric, (state->>'parcelVersion')::uuid,
            state->'holders', state->'charges', state->'restrictions');
    IF state->>'parcelVersion' IS NOT NULL THEN
        INSERT INTO title_parcel (title_id, version_no, parcel_version_id) VALUES (title, next_no, (state->>'parcelVersion')::uuid);
    END IF;
    IF i.kind = 'TRANSFER' THEN
        UPDATE pending_transaction SET status = 'REGISTERED', closed_by = p_actor, closed_at = clock_timestamp() WHERE id = i.completes_transaction_id;
    END IF;
END;
$$;

-- --- Reading -------------------------------------------------------------------------------------------------------------------

-- "Approved, to register": procedure A's CREATIONs and procedure B's TRANSFERs, with what each registers and its title.
DROP FUNCTION register_case_queue(int, int);
CREATE FUNCTION register_case_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, parcel_reference text, decided_at timestamptz,
               problems text[], may_propose boolean, inscription_id uuid, inscription_status text, owners text, registers text,
               title_number text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT q.id, q.reference, q.office_code, q.type_code, q.parcel_reference, q.decided_at, q.problems,
           cardinality(q.problems) = 0 AND NOT register_case_handler(q.id, current_actor()),
           q.inscription_id, q.inscription_status, q.owners, q.registers, q.title_number
      FROM (SELECT a.id, a.reference::text AS reference, f.code AS office_code, a.type_code, a.parcel_reference::text AS parcel_reference,
                   d.at AS decided_at, application_procedure(a.id) ->> 'registers' AS registers, t.number AS title_number,
                   CASE application_procedure(a.id) ->> 'registers' WHEN 'TRANSFER' THEN register_transfer_case_problems(a.id, NULL)
                        ELSE register_case_problems(a.id, NULL) END AS problems,
                   i.id AS inscription_id, i.status AS inscription_status,
                   (SELECT string_agg(format('%s (%s/%s)', (case_identity_latest(cp.id)).full_name, sh.share_num, sh.share_den), '; '
                                      ORDER BY cp.ref)
                      FROM case_ownership_share sh JOIN case_person cp ON cp.id = sh.person_id
                     WHERE sh.statement_id = d.ownership_statement_id) AS owners
              FROM application a
              JOIN office f ON f.id = a.office_id
              JOIN decision d ON d.application_id = a.id AND d.round = a.review_round AND d.outcome = 'APPROVED'
              LEFT JOIN land_title t ON t.id = a.title_id
              LEFT JOIN inscription i ON i.application_id = a.id AND i.status IN ('PROPOSED', 'CONFIRMATION_PENDING')
             WHERE a.state = 'APPROVED'
               AND application_procedure(a.id) ->> 'registers' IN ('CREATION', 'TRANSFER')
               AND holds_at(current_actor(), 'register.propose', a.office_id)
               AND NOT EXISTS (SELECT 1 FROM inscription r WHERE r.application_id = a.id AND r.status = 'REGISTERED')
             ORDER BY d.at, a.id
             LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0)) q
     ORDER BY q.decided_at, q.id
$$;

-- The conservators' evidence (step 5a, C2), with what a procedure B case adds: its title, the transferors as transcribed and
-- checked, and its deposit. The owners are the decided statement's: the intended owners, or the transferees.
CREATE OR REPLACE FUNCTION register_case_evidence(p_application uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT jsonb_build_object(
        'reference', a.reference,
        'capacity', a.declaration ->> 'capacity',
        'registers', application_procedure(a.id) ->> 'registers',
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
        'transferors', (SELECT coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                        'ref', cp.ref, 'kind', cp.kind, 'fullName', i.full_name, 'identityKind', i.identity_kind, 'identityNumber', i.identity_number,
                        'identityVersion', i.version_no, 'verification', i.verification, 'sourceFile', i.source_file_id,
                        'check', (SELECT jsonb_build_object('outcome', c.outcome, 'by', cu.display_name, 'at', c.checked_at)
                                    FROM case_party_check c JOIN app_user cu ON cu.id = c.checked_by
                                   WHERE c.target_kind = 'IDENTITY' AND c.target_id = i.id))) ORDER BY cp.ref), '[]')
                     FROM case_person cp JOIN case_role r ON r.person_id = cp.id AND r.role = 'TRANSFEROR'
                     CROSS JOIN LATERAL case_identity_latest(cp.id) i
                    WHERE cp.application_id = a.id),
        'title', (SELECT jsonb_build_object('id', t.id, 'number', t.number, 'version', title_current_version(t.id))
                    FROM land_title t WHERE t.id = a.title_id),
        'deposit', (SELECT jsonb_build_object('id', x.id, 'status', x.status, 'channel', x.channel, 'depositedAt', x.deposited_at)
                      FROM pending_transaction x WHERE x.id = (case_open_deposit(a.id)).id),
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
      JOIN decision d ON d.application_id = a.id AND d.round = a.review_round AND d.outcome = 'APPROVED'
     WHERE a.id = p_application AND a.state = 'APPROVED' AND case_parties_required(a.id)
       AND (holds_at(current_actor(), 'register.propose', a.office_id) OR holds_at(current_actor(), 'register.inscribe', a.office_id)
            OR holds_at(current_actor(), 'register.confirm', a.office_id))
$$;

-- The registration a case led to, once registered: a CREATION (the title it created) or a TRANSFER (the title it changed);
-- its number, kind and time, never the holders or the history. For the applicant and the case's readers.
DROP FUNCTION application_registration(uuid);
CREATE FUNCTION application_registration(p_application uuid)
RETURNS TABLE (title_number text, registered_at timestamptz, kind text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.number, i.registered_at, i.kind
      FROM application a
      JOIN inscription i ON i.application_id = a.id AND i.status = 'REGISTERED'
      JOIN land_title t ON t.id = i.title_id
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor())
            OR holds_at(current_actor(), 'register.read', a.office_id))
$$;

-- The header of a case's procedure for its readers (procedure_readable, V32): its reference, state, type, office, version and
-- round, nothing else. A conservator records procedure B's stages without application.read at the office, so the header is
-- read here rather than through the application's row policy, which would hide the row; the case's form, parcel and
-- documents stay behind that policy.
CREATE FUNCTION procedure_case_header(p_application uuid)
RETURNS TABLE (id uuid, reference text, state text, type_code text, office_code text, version bigint, review_round int)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.id, a.reference::text, a.state::text, a.type_code::text, application_office_code(a.id)::text, a.version::bigint, a.review_round
      FROM application a
     WHERE a.id = p_application AND procedure_readable(a.id, current_actor())
$$;
REVOKE ALL ON FUNCTION procedure_case_header(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION procedure_case_header(uuid) TO snigf_app;

-- The case's open deposit, its title and its duty receipts, one row per receipt (or one with none), for whoever may record
-- procedure B's stages at the case's office: the conservator cites a receipt in DUTIES_EVIDENCE (D24). Never the deed,
-- the depositor or the other documents; nothing once the deposit is closed.
CREATE FUNCTION procedure_case_deposit(p_application uuid)
RETURNS TABLE (deposit_id uuid, status text, channel text, deposited_at timestamptz, title_number text, receipt_id uuid, receipt_name text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.id, t.status, t.channel, t.deposited_at, lt.number::text, d.id, d.original_name
      FROM application a
      JOIN pending_transaction t ON t.id = (case_open_deposit(a.id)).id
      JOIN land_title lt ON lt.id = t.title_id
      LEFT JOIN deposit_document d ON d.transaction_id = t.id AND d.category = 'TAX_OR_DUTY'
     WHERE a.id = p_application AND holds_at(current_actor(), 'procedure.stage.title', a.office_id)
     ORDER BY d.uploaded_at, d.id
$$;
REVOKE ALL ON FUNCTION procedure_case_deposit(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION procedure_case_deposit(uuid) TO snigf_app;

-- --- The audit subjects: a deposit that names a case also belongs to the case ---------------------------------------------------

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
        WHEN p_type = 'LAND_TITLE' THEN
            (SELECT CASE WHEN t.application_id IS NULL THEN jsonb_build_array(audit_subject('LAND_TITLE', t.id, t.office_id))
                         ELSE jsonb_build_array(audit_subject('LAND_TITLE', t.id, t.office_id),
                                                audit_subject('APPLICATION', t.application_id, t.office_id)) END
               FROM land_title t WHERE t.id = p_id)
        WHEN p_type = 'INSCRIPTION' THEN
            (SELECT CASE WHEN i.application_id IS NULL
                         THEN jsonb_build_array(audit_subject('LAND_TITLE', coalesce(i.title_id, i.new_title_id), i.office_id))
                         ELSE jsonb_build_array(audit_subject('LAND_TITLE', coalesce(i.title_id, i.new_title_id), i.office_id),
                                                audit_subject('APPLICATION', i.application_id, i.office_id)) END
               FROM inscription i WHERE i.id = p_id)
        WHEN p_type = 'PENDING_TRANSACTION' THEN
            (SELECT CASE WHEN t.application_id IS NULL THEN jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id))
                         ELSE jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id),
                                                audit_subject('APPLICATION', t.application_id, t.office_id)) END
               FROM pending_transaction t WHERE t.id = p_id)
        WHEN p_type = 'TRANSACTION_CONFLICT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', c.title_id, c.office_id)) FROM transaction_conflict c WHERE c.id = p_id)
        WHEN p_type = 'DEPOSIT_DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id))
               FROM deposit_document d JOIN pending_transaction t ON t.id = d.transaction_id WHERE d.id = p_id)
        ELSE NULL
    END
$$;

-- --- Grants -----------------------------------------------------------------------------------------------------------------

REVOKE ALL ON FUNCTION case_owner_role(uuid), case_declaration_problems(jsonb, text), case_transfer_open(uuid), application_title_resolve(),
    deposit_case_resolve(land_title, text), transaction_deposit(uuid, text, uuid, uuid, text[], interval, text),
    transaction_deposit_professional(uuid, text, jsonb, jsonb, interval, text), case_open_deposit(uuid),
    case_transferor_match(uuid, uuid, int), title_holder_rights(uuid, int), title_blocked(uuid, int),
    register_transfer_case_problems(uuid, uuid), register_inscription_case_problems(uuid), inscription_case_transfer_guard(),
    register_propose_transfer_from_case(uuid, text), register_case_queue(int, int), application_registration(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION transaction_deposit(uuid, text, uuid, uuid, text[], interval, text),
    transaction_deposit_professional(uuid, text, jsonb, jsonb, interval, text), register_propose_transfer_from_case(uuid, text),
    register_case_queue(int, int), application_registration(uuid) TO snigf_app;
