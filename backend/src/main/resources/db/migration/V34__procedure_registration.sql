-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V34: procedure A's registration (Slice 4, ADR-042 step 5, §4, D27, D29). DEMO only: "registered" means registered in
-- the SNIGF DEMO register; nothing here is a legal registration, a title document or a certificate.
--
--   inscription.application_id  the approved case a CREATION is proposed from; set only by register_propose_from_case(),
--                              never changed. One CREATION in progress or registered per case.
--   land_title.application_id  the case a title was created from. ck_land_title_synthetic_only is replaced: a title is
--                              SYNTHETIC_DEMO without a case, or IMMATRICULATION with one; MORCELLEMENT stays refused.
--   register_case_problems()   what stops a CREATION from a case now: not found, a procedure that does not register a
--                              CREATION (procedure B and the generic type alike), the case not approved, an objection open
--                              or upheld after the decision (V33's procedure_registration_problems()), the procedure's gate
--                              no longer empty, a title already created from it, a CREATION from it already in progress,
--                              the parcel already resting under an active title or awaiting another CREATION.
--   register_case_handler()    whoever applied, received, reviewed, recommended, decided, recorded a stage or handled an
--                              objection on the case: they never propose nor register its title.
--   register_propose_from_case()  the conservator's proposal (§4): the case's parcel at its current version and its area,
--                              the applicant as the sole holder in full ownership (a party of the office linked to their
--                              account, their DEMO identity transcribed by the conservator from the case's identity
--                              document, since SNIGF verifies no identity), the decision as the authority, its cited
--                              versions as the evidence. Registration stays ADR-040's: one conservator signs it.
--   register_apply()           replaced: a CREATION from a case is checked again under the case's lock when it is signed
--                              (the case may have changed since the proposal), and creates an IMMATRICULATION title.
--   register_case_queue()      "Approved, to register": the approved cases of procedure A at the conservator's offices
--                              with no title yet, each with what stops it and its proposal if there is one.
--   application_registration() the title a case was registered under, for its applicant and its readers.
-- The signed bytes of a CREATION from a case also name the case; those of every other inscription are unchanged.
-- An objection is no longer received once the case's title is registered (CASE_REGISTERED).
-- Registration issues no document: the DEMO notice and the delivery are later acts (step 7, D29).

SET search_path = snigf, public;

-- --- The case on the inscription and on the title ---------------------------------------------------------------

ALTER TABLE inscription ADD COLUMN application_id uuid REFERENCES application (id);
ALTER TABLE inscription ADD CONSTRAINT ck_inscription_case CHECK (application_id IS NULL OR kind = 'CREATION');
-- One CREATION in progress or registered per case; a rejected or withdrawn proposal frees it.
CREATE UNIQUE INDEX ux_inscription_case_creation ON inscription (application_id)
    WHERE application_id IS NOT NULL AND status IN ('PROPOSED', 'CONFIRMATION_PENDING', 'REGISTERED');

-- The case an inscription was proposed from never changes (inscription_guard keeps the rest).
CREATE FUNCTION inscription_case_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.application_id IS DISTINCT FROM OLD.application_id THEN
        RAISE EXCEPTION 'the case of an inscription never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inscription_case_guard BEFORE UPDATE ON inscription FOR EACH ROW EXECUTE FUNCTION inscription_case_guard();

ALTER TABLE land_title ADD COLUMN application_id uuid UNIQUE REFERENCES application (id);
ALTER TABLE land_title DROP CONSTRAINT ck_land_title_synthetic_only;
ALTER TABLE land_title ADD CONSTRAINT ck_land_title_origin CHECK (
    (origin = 'SYNTHETIC_DEMO' AND application_id IS NULL) OR (origin = 'IMMATRICULATION' AND application_id IS NOT NULL));

-- --- The signed bytes: a CREATION from a case also names it ------------------------------------------------------
-- Appended only when there is a case, so the bytes of every inscription signed before V34 are unchanged.

CREATE OR REPLACE FUNCTION inscription_canonical(p_inscription uuid) RETURNS bytea
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    encoded bytea := inscription_canonical_fields(p_inscription);
    n       bigint;
    d       record;
    app     uuid;
BEGIN
    SELECT count(*) INTO n FROM inscription i JOIN deposit_document x ON x.transaction_id = i.completes_transaction_id WHERE i.id = p_inscription;
    IF n > 0 THEN
        encoded := encoded || canonical_text(n::text);
        FOR d IN SELECT x.id, x.sha256 FROM inscription i JOIN deposit_document x ON x.transaction_id = i.completes_transaction_id
                  WHERE i.id = p_inscription ORDER BY x.id LOOP
            encoded := encoded || canonical_text(d.id::text) || canonical_text(d.sha256::text);
        END LOOP;
    END IF;
    SELECT i.application_id INTO app FROM inscription i WHERE i.id = p_inscription;
    IF app IS NOT NULL THEN
        encoded := encoded || canonical_text('case') || canonical_text(app::text);
    END IF;
    RETURN encoded;
END;
$$;

-- --- What a registration from a case needs -----------------------------------------------------------------------

-- Whoever handled the case at any step. They never propose nor register its title.
CREATE FUNCTION register_case_handler(p_application uuid, p_actor uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT p_actor IS NOT NULL AND (
        EXISTS (SELECT 1 FROM application a WHERE a.id = p_application AND a.applicant_id = p_actor)
        OR EXISTS (SELECT 1 FROM completeness_record r WHERE r.application_id = p_application AND r.actor_id = p_actor)
        OR EXISTS (SELECT 1 FROM case_assignment c WHERE c.application_id = p_application AND c.assignee_id = p_actor)
        OR EXISTS (SELECT 1 FROM review_step s WHERE s.application_id = p_application AND s.actor_id = p_actor)
        OR EXISTS (SELECT 1 FROM decision d WHERE d.application_id = p_application AND d.decided_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_stage_record r WHERE r.application_id = p_application AND r.recorded_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_objection o WHERE o.application_id = p_application AND o.recorded_by = p_actor)
        OR EXISTS (SELECT 1 FROM procedure_objection o JOIN procedure_objection_decision od ON od.objection_id = o.id
                    WHERE o.application_id = p_application AND od.decided_by = p_actor))
$$;

-- What stops a CREATION from the case now, as reason codes, in the order they are reported. p_except is the CREATION
-- being registered, which is not "another one in progress".
CREATE FUNCTION register_case_problems(p_application uuid, p_except uuid) RETURNS text[]
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a         application;
    proc      jsonb;
    problems  text[] := ARRAY[]::text[];
BEGIN
    SELECT * INTO a FROM application WHERE id = p_application;
    IF a.id IS NULL OR a.office_id IS NULL THEN
        RETURN ARRAY['CASE_NOT_FOUND'];
    END IF;
    proc := application_procedure(a.id);
    -- Procedure B keeps its title (D28) and the generic type registers nothing (D29): neither creates one.
    IF proc IS NULL OR proc ->> 'registers' IS DISTINCT FROM 'CREATION' THEN
        RETURN ARRAY['PROCEDURE_NOT_CREATION'];
    END IF;
    problems := problems || procedure_registration_problems(a.id);
    -- The gate again (a parcel corrected since the approval, say); its objection codes are reported above already.
    IF a.state = 'APPROVED' AND EXISTS (SELECT 1 FROM unnest(procedure_gate_problems(a.id)) g(code)
                                         WHERE g.code <> 'OBJECTION_OPEN' AND g.code NOT LIKE 'OBJECTION_REVIEW_PENDING:%') THEN
        problems := problems || 'PROCEDURE_INCOMPLETE'::text;
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

-- --- The proposal from the case ---------------------------------------------------------------------------------

-- p_holder: {"name", "identityKind", "identityNumber"}, the applicant's DEMO identity as the conservator transcribes it
-- from the case's identity document. The applicant is the sole holder, in full ownership; a different holder is
-- ADR-040's CORRECTION afterwards, under dual control and with a reason.
CREATE FUNCTION register_propose_from_case(p_application uuid, p_holder jsonb, p_reason text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor     uuid := current_actor();
    a         application;
    d         decision;
    pv        parcel_version;
    problems  text[];
    h_name    text := btrim(p_holder ->> 'name');
    id_kind   text := btrim(p_holder ->> 'identityKind');
    id_number text := btrim(p_holder ->> 'identityNumber');
    party     uuid;
    new_id    uuid;
BEGIN
    -- The case's lock orders this proposal against another one, and against its registration.
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
    IF p_holder IS NULL OR jsonb_typeof(p_holder) <> 'object' THEN
        PERFORM register_refuse('HOLDER_INVALID');
    END IF;
    IF h_name IS NULL OR length(h_name) NOT BETWEEN 3 AND 200 OR h_name NOT LIKE '%DEMO%'
       OR id_kind IS NULL OR id_kind !~ '^[A-Z_]{2,40}$'
       OR id_number IS NULL OR length(id_number) NOT BETWEEN 5 AND 64 OR id_number NOT LIKE '%DEMO%'
       OR EXISTS (SELECT 1 FROM jsonb_object_keys(p_holder) k WHERE k NOT IN ('name', 'identityKind', 'identityNumber')) THEN
        PERFORM register_refuse('HOLDER_INVALID');
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

    INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, account_id, created_by)
    VALUES (a.office_id, 'NATURAL_PERSON', h_name, id_kind, id_number, a.applicant_id, actor)
    RETURNING id INTO party;
    INSERT INTO inscription (title_id, new_title_id, office_id, kind, expected_version, authority_type, authority_reference, reason,
                             evidence_references, evidence_versions, area_m2, parcel_version_id, proposed_by, application_id)
    VALUES (NULL, uuidv7(), a.office_id, 'CREATION', 0, 'DECISION', a.reference, btrim(p_reason),
            ARRAY[a.reference, 'DECISION:' || d.id::text], d.cited_versions, pv.area_m2, pv.id, actor, a.id)
    RETURNING id INTO new_id;
    INSERT INTO inscription_holder (inscription_id, party_id, interest, share_num, share_den) VALUES (new_id, party, 'OWNERSHIP', 1, 1);
    IF register_actor_interested(actor, NULL, new_id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN new_id;
END;
$$;

-- --- Registration: a CREATION from a case is checked again when it is signed -------------------------------------------

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
        -- D19: what the signature binds must not change underneath it.
        IF EXISTS (SELECT 1 FROM deposit_document_replacement r WHERE r.transaction_id = i.completes_transaction_id AND r.status = 'PENDING') THEN
            PERFORM register_refuse('REPLACEMENT_PENDING');
        END IF;
    END IF;
    IF i.kind = 'CREATION' AND i.application_id IS NOT NULL THEN
        -- The case may have changed since the proposal: an objection received, upheld after the decision.
        PERFORM 1 FROM application WHERE id = i.application_id FOR UPDATE;
        IF register_case_handler(i.application_id, p_actor) THEN
            PERFORM register_refuse('CASE_HANDLER_CANNOT_REGISTER');
        END IF;
        problems := register_case_problems(i.application_id, i.id);
        IF cardinality(problems) > 0 THEN
            PERFORM register_refuse(problems[1]);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM application a JOIN parcel p ON p.id = a.parcel_id
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

-- --- No objection once the title is registered -------------------------------------------------------------------------

CREATE FUNCTION procedure_objection_after_registration() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM land_title t WHERE t.application_id = NEW.application_id) THEN
        PERFORM procedure_refuse('CASE_REGISTERED');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER procedure_objection_after_registration BEFORE INSERT ON procedure_objection
    FOR EACH ROW EXECUTE FUNCTION procedure_objection_after_registration();

-- --- Reading ----------------------------------------------------------------------------------------------------------

-- "Approved, to register": the approved cases of a procedure that creates a title, at the offices where the conservator
-- proposes, with no title yet. Each says what stops it, whether this conservator may propose, and its proposal if any.
CREATE FUNCTION register_case_queue(p_limit int, p_offset int)
RETURNS TABLE (application_id uuid, reference text, office_code text, type_code text, parcel_reference text, decided_at timestamptz,
               problems text[], may_propose boolean, inscription_id uuid, inscription_status text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT a.id, a.reference::text, f.code, a.type_code, a.parcel_reference::text, d.at,
           register_case_problems(a.id, NULL),
           cardinality(register_case_problems(a.id, NULL)) = 0 AND NOT register_case_handler(a.id, current_actor()),
           i.id, i.status
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

-- The title a case was registered under, once registered: its number and time, never its holders or history. For the
-- applicant and the case's readers.
CREATE FUNCTION application_registration(p_application uuid)
RETURNS TABLE (title_number text, registered_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.number, i.registered_at
      FROM application a
      JOIN land_title t ON t.application_id = a.id
      JOIN inscription i ON i.id = t.creation_inscription_id
     WHERE a.id = p_application
       AND (a.applicant_id = current_actor() OR procedure_readable(a.id, current_actor())
            OR holds_at(current_actor(), 'register.read', a.office_id))
$$;

-- --- The audit subjects: an inscription from a case also belongs to the case -------------------------------------------

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
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id)) FROM pending_transaction t WHERE t.id = p_id)
        WHEN p_type = 'TRANSACTION_CONFLICT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', c.title_id, c.office_id)) FROM transaction_conflict c WHERE c.id = p_id)
        WHEN p_type = 'DEPOSIT_DOCUMENT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id))
               FROM deposit_document d JOIN pending_transaction t ON t.id = d.transaction_id WHERE d.id = p_id)
        ELSE NULL
    END
$$;

-- --- Grants -------------------------------------------------------------------------------------------------------------

REVOKE ALL ON FUNCTION inscription_case_guard(), procedure_objection_after_registration(), register_case_handler(uuid, uuid),
    register_case_problems(uuid, uuid), register_propose_from_case(uuid, jsonb, text), register_case_queue(int, int),
    application_registration(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION register_propose_from_case(uuid, jsonb, text), register_case_queue(int, int), application_registration(uuid)
    TO snigf_app;
