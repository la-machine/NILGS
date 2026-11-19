-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V31: procedures in the application type catalogue (Slice 4, ADR-042 step 2, decisions D23 and D26). DEMO only.
--
-- A procedure is part of an application type's version, so a case keeps the procedure of the version it was
-- submitted under (ADR-032):
--   procedure_stage          the stage catalogue, fixed here and in the code (ProcedureStage): each stage belongs to one
--                            procedure (A, immatriculation directe; B, mutation totale), has a rank within it, may need an
--                            earlier stage, and names the permission of whoever records it. Never updated, never deleted.
--   requirements.procedure   optional on a type version: {"code", "stages", "registers", "delivery"}. Checked on insert by
--                            application_procedure_problems(): known stages of that procedure only, each once, in rank order,
--                            with the stages they need before them, and the register act of that procedure.
-- Seven permissions, never professional. Only procedure.commission.report is delegable: a member designated for a
-- commission receives it by a special grant (ADR-025, two administrators). A new baseline role, COMMISSION_SECRETARY.
-- Nothing records a stage yet (V32); no stage, period or actor here is a validated legal requirement (ADR-040 §10).

SET search_path = snigf, public;

-- --- Permissions and roles ---------------------------------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('procedure.stage.cadastral', 'BUSINESS', false, ARRAY['OFFICE'],
     'Record a case''s cadastral review and field visit', 'Enregistrer l''examen cadastral et la visite de terrain d''un dossier'),
    ('procedure.commission.report', 'BUSINESS', true, ARRAY['OFFICE'],
     'Record and validate the commission''s report on a field visit', 'Enregistrer et valider le procès-verbal de la commission sur une visite de terrain'),
    ('procedure.stage.publish', 'BUSINESS', false, ARRAY['OFFICE'],
     'Publish a case''s notice, opening its objection period', 'Publier l''avis d''un dossier et ouvrir sa période d''opposition'),
    ('procedure.stage.title', 'BUSINESS', false, ARRAY['OFFICE'],
     'Record the verification of a case''s title and of its duty receipt', 'Enregistrer la vérification du titre d''un dossier et de sa quittance de droits'),
    ('procedure.objection.record', 'BUSINESS', false, ARRAY['OFFICE'],
     'Record an objection received at the counter', 'Enregistrer une opposition reçue au guichet'),
    ('procedure.objection.decide', 'BUSINESS', false, ARRAY['OFFICE'],
     'Decide an objection recorded by someone else', 'Statuer sur une opposition enregistrée par une autre personne'),
    ('procedure.delivery.record', 'BUSINESS', false, ARRAY['OFFICE'],
     'Record the collection of a registration notice', 'Enregistrer le retrait d''un avis d''inscription');

INSERT INTO role_definition (code, name_fr, name_en, system, assignable) VALUES
    ('COMMISSION_SECRETARY', 'Secrétaire de commission (démonstration)', 'Commission secretary (demonstration)', true, true);
-- Baseline roles change only here, reviewed, never at run time.
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('CADASTRAL_OFFICER', 'procedure.stage.cadastral', 'OFFICE'),
    ('COMMISSION_SECRETARY', 'parcel.read', 'ANY'),
    ('COMMISSION_SECRETARY', 'application.read', 'OFFICE'),
    ('COMMISSION_SECRETARY', 'procedure.commission.report', 'OFFICE'),
    ('INTAKE_OFFICER', 'procedure.stage.publish', 'OFFICE'),
    ('INTAKE_OFFICER', 'procedure.objection.record', 'OFFICE'),
    ('INTAKE_OFFICER', 'procedure.delivery.record', 'OFFICE'),
    ('SUPERVISOR', 'procedure.objection.decide', 'OFFICE'),
    ('CONSERVATOR', 'procedure.stage.title', 'OFFICE');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- --- The stage catalogue -----------------------------------------------------------------------------------

CREATE TABLE procedure_stage (
    code        text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z_]{2,39}$'),
    procedure   text NOT NULL CHECK (procedure IN ('A', 'B')),
    rank        int NOT NULL CHECK (rank BETWEEN 1 AND 99),
    requires    text REFERENCES procedure_stage (code),
    permission  text NOT NULL REFERENCES permission (code),
    label_en    text NOT NULL CHECK (length(label_en) BETWEEN 3 AND 120),
    label_fr    text NOT NULL CHECK (length(label_fr) BETWEEN 3 AND 120),
    UNIQUE (procedure, rank)
);
CREATE TRIGGER procedure_stage_append_only BEFORE UPDATE OR DELETE ON procedure_stage
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER procedure_stage_no_truncate BEFORE TRUNCATE ON procedure_stage
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

INSERT INTO procedure_stage (code, procedure, rank, requires, permission, label_en, label_fr) VALUES
    ('CADASTRAL_REVIEW', 'A', 10, NULL, 'procedure.stage.cadastral', 'Cadastral review', 'Examen cadastral'),
    ('FIELD_VISIT', 'A', 20, NULL, 'procedure.stage.cadastral', 'Field visit', 'Visite de terrain'),
    ('COMMISSION_REPORT', 'A', 30, 'FIELD_VISIT', 'procedure.commission.report', 'Commission''s report', 'Procès-verbal de la commission'),
    ('PUBLICATION', 'A', 40, NULL, 'procedure.stage.publish', 'Publication and objections', 'Publication et oppositions'),
    ('TITLE_VERIFICATION', 'B', 10, NULL, 'procedure.stage.title', 'Title verification', 'Vérification du titre'),
    ('DUTIES_EVIDENCE', 'B', 20, NULL, 'procedure.stage.title', 'Duty receipt checked', 'Quittance des droits vérifiée');

-- What each procedure registers once its case is approved (ADR-042 §4, §5).
CREATE FUNCTION procedure_registers(p_procedure text) RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE p_procedure WHEN 'A' THEN 'CREATION' WHEN 'B' THEN 'TRANSFER' END
$$;

-- What is wrong with a type version's procedure, as stable reasons; empty when it has none or it is valid.
CREATE FUNCTION application_procedure_problems(p_requirements jsonb) RETURNS text[]
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    proc      jsonb := p_requirements -> 'procedure';
    problems  text[] := ARRAY[]::text[];
    code      text;
    stage     jsonb;
    known     procedure_stage;
    seen      text[] := ARRAY[]::text[];
    last_rank int := 0;
BEGIN
    IF proc IS NULL OR jsonb_typeof(proc) = 'null' THEN
        RETURN problems;
    END IF;
    IF jsonb_typeof(proc) <> 'object' THEN
        RETURN ARRAY['PROCEDURE_NOT_AN_OBJECT'];
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(proc) k WHERE k NOT IN ('code', 'stages', 'registers', 'delivery')) THEN
        problems := problems || 'PROCEDURE_UNKNOWN_KEY'::text;
    END IF;
    code := proc ->> 'code';
    IF jsonb_typeof(proc -> 'code') IS DISTINCT FROM 'string' OR code NOT IN ('A', 'B') THEN
        problems := problems || 'PROCEDURE_UNKNOWN'::text;
    END IF;
    IF jsonb_typeof(proc -> 'delivery') IS DISTINCT FROM 'boolean' THEN
        problems := problems || 'PROCEDURE_DELIVERY_NOT_BOOLEAN'::text;
    END IF;
    IF jsonb_typeof(proc -> 'registers') IS DISTINCT FROM 'string'
       OR proc ->> 'registers' IS DISTINCT FROM procedure_registers(code) THEN
        problems := problems || 'PROCEDURE_REGISTERS_MISMATCH'::text;
    END IF;
    IF jsonb_typeof(proc -> 'stages') IS DISTINCT FROM 'array' OR jsonb_array_length(proc -> 'stages') = 0 THEN
        RETURN problems || 'PROCEDURE_NO_STAGES'::text;
    END IF;
    FOR stage IN SELECT value FROM jsonb_array_elements(proc -> 'stages') LOOP
        SELECT * INTO known FROM procedure_stage s WHERE jsonb_typeof(stage) = 'string' AND s.code = stage #>> '{}';
        IF known.code IS NULL THEN
            problems := problems || ('STAGE_UNKNOWN:' || coalesce(stage #>> '{}', '?'));
            CONTINUE;
        END IF;
        IF known.procedure IS DISTINCT FROM code THEN
            problems := problems || ('STAGE_OF_ANOTHER_PROCEDURE:' || known.code);
            CONTINUE;
        END IF;
        IF known.code = ANY (seen) THEN
            problems := problems || ('STAGE_TWICE:' || known.code);
        ELSIF known.rank <= last_rank THEN
            problems := problems || ('STAGE_OUT_OF_ORDER:' || known.code);
        END IF;
        IF known.requires IS NOT NULL AND NOT known.requires = ANY (seen) THEN
            problems := problems || ('STAGE_NEEDS:' || known.code || '<' || known.requires);
        END IF;
        seen := seen || known.code;
        last_rank := greatest(last_rank, known.rank);
    END LOOP;
    RETURN problems;
END;
$$;

CREATE FUNCTION application_type_procedure_check() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    problems text[] := application_procedure_problems(NEW.requirements);
BEGIN
    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION 'invalid procedure for %: %', NEW.code, array_to_string(problems, ', ')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER application_type_procedure_check BEFORE INSERT ON application_type
    FOR EACH ROW EXECUTE FUNCTION application_type_procedure_check();

-- The procedure of an application, from the type version it was submitted under (NULL when it has none).
CREATE FUNCTION application_procedure(p_application uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT nullif(t.requirements -> 'procedure', 'null'::jsonb)
      FROM application a JOIN application_type t ON t.id = a.type_version_id
     WHERE a.id = p_application
$$;

REVOKE ALL ON procedure_stage FROM PUBLIC;
GRANT SELECT ON procedure_stage TO snigf_app;
REVOKE ALL ON FUNCTION application_procedure_problems(jsonb), application_procedure(uuid), procedure_registers(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION application_procedure_problems(jsonb), application_procedure(uuid), procedure_registers(text) TO snigf_app;
