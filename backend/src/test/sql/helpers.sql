-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- Test helpers, installed by infra/scripts/db-test.sh after the migrations.

-- Runs one statement as another database role, then returns to the session role.
-- If the statement fails, pgTAP's exception block rolls back the subtransaction,
-- which also undoes SET LOCAL ROLE, so later assertions run as the superuser again.
CREATE FUNCTION tap.run_as(p_role text, p_sql text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('SET LOCAL ROLE %I', p_role);
    EXECUTE p_sql;
    RESET ROLE;
END;
$$;

-- Synthetic fixture: one demo office and users, created as the schema owner.
CREATE FUNCTION tap.seed_basic() RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO snigf.office (id, code, name_fr, name_en) VALUES
        ('00000000-0000-0000-0000-0000000000a1', 'DEMO1', 'Bureau de démonstration 1', 'Demo office 1');
    INSERT INTO snigf.app_user (id, email, display_name, password_hash, account_type) VALUES
        ('00000000-0000-0000-0000-0000000000b1', 'officer@demo.invalid',   'Demo Officer',   'x', 'STAFF'),
        ('00000000-0000-0000-0000-0000000000b2', 'admin@demo.invalid',     'Demo Admin',     'x', 'STAFF'),
        ('00000000-0000-0000-0000-0000000000b3', 'applicant@demo.invalid', 'Demo Applicant', 'x', 'APPLICANT');
END;
$$;

-- ADR-043: a declaration with the applicant as the sole intended owner, in full ownership.
CREATE FUNCTION tap.self_declaration(p_name text DEFAULT 'DEMO Applicant Owner') RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('capacity', 'SELF',
        'persons', jsonb_build_array(jsonb_build_object('ref', 'p1', 'kind', 'NATURAL_PERSON', 'roles', jsonb_build_array('APPLICANT', 'INTENDED_OWNER'),
                                                        'fullName', p_name, 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-SELF-0001',
                                                        'phone', '+237 600000001')),
        'shares', jsonb_build_array(jsonb_build_object('person', 'p1', 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)))
$$;

-- ADR-042 step 6 (D37): a procedure B declaration, the applicant (p1) the sole transferee in full ownership, one transferor
-- (p2) with the identity given, which is to match the title's holder.
CREATE FUNCTION tap.transfer_declaration(p_transferor_name text DEFAULT 'DEMO Seller', p_transferor_number text DEFAULT 'DEMO-ID-SELLER-01')
RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('capacity', 'SELF',
        'persons', jsonb_build_array(
            jsonb_build_object('ref', 'p1', 'kind', 'NATURAL_PERSON', 'roles', jsonb_build_array('APPLICANT', 'TRANSFEREE'),
                               'fullName', 'DEMO Buyer', 'identityKind', 'NATIONAL_ID', 'identityNumber', 'DEMO-ID-BUYER-01',
                               'phone', '+237 600000002'),
            jsonb_build_object('ref', 'p2', 'kind', 'NATURAL_PERSON', 'roles', jsonb_build_array('TRANSFEROR'),
                               'fullName', p_transferor_name, 'identityKind', 'NATIONAL_ID', 'identityNumber', p_transferor_number)),
        'shares', jsonb_build_array(jsonb_build_object('person', 'p1', 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)))
$$;

-- ADR-043: takes a case's persons through intake as the declaration said them: p_recorder scans a document and
-- transcribes each identity, the ownership statement and each mandate; p_checker checks each one MATCHES.
CREATE FUNCTION tap.parties_ready(p_application uuid, p_recorder uuid, p_checker uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    id_file   uuid;
    own_file  uuid;
    man_file  uuid;
    p         record;
    shares    jsonb;
    target    uuid;
BEGIN
    PERFORM set_config('snigf.actor_id', p_recorder::text, true);
    id_file := snigf.case_party_file_add(p_application, 'IDENTITY_DOCUMENT', md5(gen_random_uuid()::text), repeat('c', 64), 10,
                                         'application/pdf', 'identity.pdf');
    own_file := snigf.case_party_file_add(p_application, 'OWNERSHIP_EVIDENCE', md5(gen_random_uuid()::text), repeat('d', 64), 10,
                                          'application/pdf', 'evidence.pdf');
    man_file := snigf.case_party_file_add(p_application, 'MANDATE', md5(gen_random_uuid()::text), repeat('e', 64), 10,
                                          'application/pdf', 'mandate.pdf');
    FOR p IN SELECT i.id AS identity_id, i.person_id, i.full_name, i.identity_kind, i.identity_number, i.phone
               FROM snigf.case_person cp CROSS JOIN LATERAL snigf.case_identity_latest(cp.id) i WHERE cp.application_id = p_application LOOP
        PERFORM snigf.case_identity_record(p.person_id, jsonb_strip_nulls(jsonb_build_object('fullName', p.full_name, 'identityKind', p.identity_kind,
            'identityNumber', p.identity_number, 'phone', p.phone)), id_file, NULL, p.identity_id, NULL);
    END LOOP;
    SELECT jsonb_agg(jsonb_build_object('person', s.person_id, 'interest', s.interest, 'shareNum', s.share_num, 'shareDen', s.share_den,
                                        'basisFile', own_file))
      INTO shares FROM snigf.case_ownership_share s WHERE s.statement_id = (snigf.case_statement_latest(p_application)).id;
    PERFORM snigf.case_ownership_record(p_application, shares, (snigf.case_statement_latest(p_application)).id, NULL);
    FOR p IN SELECT m.* FROM snigf.case_person cp CROSS JOIN LATERAL snigf.case_mandate_latest(cp.id) m
              WHERE cp.application_id = p_application AND m.id IS NOT NULL LOOP
        PERFORM snigf.case_mandate_record(p.representative_id, ARRAY(SELECT person_id FROM snigf.case_mandate_subject WHERE mandate_id = p.id),
                                          p.scope, man_file, p.id, NULL);
    END LOOP;
    PERFORM set_config('snigf.actor_id', p_checker::text, true);
    FOR target IN SELECT (snigf.case_identity_latest(cp.id)).id FROM snigf.case_person cp WHERE cp.application_id = p_application LOOP
        PERFORM snigf.case_party_check_record('IDENTITY', target, 'MATCHES', NULL);
    END LOOP;
    PERFORM snigf.case_party_check_record('OWNERSHIP', (snigf.case_statement_latest(p_application)).id, 'MATCHES', NULL);
    FOR target IN SELECT m.id FROM snigf.case_person cp CROSS JOIN LATERAL snigf.case_mandate_latest(cp.id) m
                   WHERE cp.application_id = p_application AND m.id IS NOT NULL LOOP
        PERFORM snigf.case_party_check_record('MANDATE', target, 'MATCHES', NULL);
    END LOOP;
END;
$$;
