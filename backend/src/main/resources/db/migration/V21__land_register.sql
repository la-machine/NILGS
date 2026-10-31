-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V21: the land register (Slice 2, ADR-040). DEMO only: every row is synthetic, "registered" means
-- registered in the SNIGF DEMO register, and nothing here is a legal registration.
--
--   register_party           a holder, beneficiary, depositor or notary as the register knows them
--   land_title               a title's identity, created by a registered CREATION
--   inscription              a numbered entry: PROPOSED -> (CONFIRMATION_PENDING ->) REGISTERED, or
--                            REJECTED / WITHDRAWN; frozen once it leaves the proposal stages
--   inscription_holder       holders and exact shares, for CREATION, TRANSFER and their corrections
--   inscription_charge       the charge or restriction of an inscription
--   inscription_signature    one per signing act: the officer's WebAuthn evidence and the RECORD
--                            signature. Dual control has two signers, so this is its own table
--   title_version            the derived state after each registered inscription, written only here
--   title_parcel             the parcel version a title version rests on
--   title_relation           parent to child titles (modelled; used from slice 4)
--   pending_transaction      a deposited transaction; one HOLDING per title
--   transaction_conflict     a second deposit against the holding one; resolved once
--   register_migration_batch modelled for future real titles; only SYNTHETIC_DEMO is possible
--
-- The application has no INSERT, UPDATE or DELETE on any of these tables: every change is one call
-- to a SECURITY DEFINER function that locks the title, checks the actor's permission at the
-- conservation and the separation of duties, and writes. A refusal is SQLSTATE RG001 with the
-- reason code as its message.

SET search_path = snigf, public;

-- --- Permissions and the conservator ------------------------------------------------------------

INSERT INTO permission (code, family, delegable, scopes, description_en, description_fr) VALUES
    ('register.read', 'BUSINESS', true, ARRAY['OFFICE'],
     'Read the land register', 'Consulter le livre foncier'),
    ('register.propose', 'BUSINESS', false, ARRAY['OFFICE'],
     'Propose an inscription or record a deposit', 'Proposer une inscription ou enregistrer un dépôt'),
    ('register.inscribe', 'BUSINESS', false, ARRAY['OFFICE'],
     'Register an inscription with a signature', 'Inscrire au livre foncier avec une signature'),
    ('register.confirm', 'BUSINESS', false, ARRAY['OFFICE'],
     'Confirm another conservator''s inscription (dual control)', 'Confirmer l''inscription d''un autre conservateur (double contrôle)'),
    ('register.transaction.decide', 'BUSINESS', false, ARRAY['OFFICE'],
     'Refuse a deposit or resolve a conflict', 'Refuser un dépôt ou résoudre un conflit');
INSERT INTO role_definition (code, name_fr, name_en, system, assignable) VALUES
    ('CONSERVATOR', 'Conservateur (démonstration)', 'Conservator (demonstration)', true, true);
-- Baseline roles change only here, reviewed, never at run time.
ALTER TABLE role_permission DISABLE TRIGGER role_permission_guard;
INSERT INTO role_permission (role_code, permission, scope) VALUES
    ('CONSERVATOR', 'parcel.read', 'ANY'),
    ('CONSERVATOR', 'register.read', 'OFFICE'),
    ('CONSERVATOR', 'register.propose', 'OFFICE'),
    ('CONSERVATOR', 'register.inscribe', 'OFFICE'),
    ('CONSERVATOR', 'register.confirm', 'OFFICE'),
    ('CONSERVATOR', 'register.transaction.decide', 'OFFICE');
ALTER TABLE role_permission ENABLE TRIGGER role_permission_guard;

-- --- Tables ---------------------------------------------------------------------------------------

CREATE TABLE register_party (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    office_id            uuid NOT NULL REFERENCES office (id),
    kind                 text NOT NULL CHECK (kind IN ('NATURAL_PERSON', 'LEGAL_PERSON', 'NOTARY')),
    -- Synthetic data only: every name and identity number says so.
    full_name            text NOT NULL CHECK (length(btrim(full_name)) BETWEEN 3 AND 200 AND full_name LIKE '%DEMO%'),
    identity_kind        text NOT NULL CHECK (identity_kind ~ '^[A-Z_]{2,40}$'),
    identity_number      text NOT NULL CHECK (length(identity_number) BETWEEN 5 AND 64 AND identity_number LIKE '%DEMO%'),
    chamber              text CHECK (chamber IS NULL OR length(btrim(chamber)) BETWEEN 2 AND 200),
    registration_number  text CHECK (registration_number IS NULL OR length(btrim(registration_number)) BETWEEN 2 AND 64),
    jurisdiction         text CHECK (jurisdiction IS NULL OR length(btrim(jurisdiction)) BETWEEN 2 AND 200),
    -- The account is not the person, and a person may have no account.
    account_id           uuid REFERENCES app_user (id),
    created_by           uuid NOT NULL REFERENCES app_user (id),
    created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    demo                 boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_notary_credential CHECK ((kind = 'NOTARY') = (chamber IS NOT NULL)
                                           AND (kind = 'NOTARY') = (registration_number IS NOT NULL)
                                           AND (kind = 'NOTARY') = (jurisdiction IS NOT NULL))
);
CREATE INDEX ix_register_party_office ON register_party (office_id, full_name);

CREATE SEQUENCE land_title_seq;

CREATE TABLE inscription (
    id                        uuid PRIMARY KEY DEFAULT uuidv7(),
    -- NULL until a CREATION is registered; the title it will create is named from the start.
    title_id                  uuid,
    new_title_id              uuid,
    office_id                 uuid NOT NULL REFERENCES office (id),
    number                    int CHECK (number IS NULL OR number >= 1),
    kind                      text NOT NULL CHECK (kind IN ('CREATION', 'TRANSFER', 'CHARGE', 'CHARGE_RELEASE', 'RESTRICTION',
                                                            'RESTRICTION_RELEASE', 'AREA_REDUCTION', 'CLOSURE', 'CANCELLATION',
                                                            'CORRECTION')),
    status                    text NOT NULL DEFAULT 'PROPOSED'
                              CHECK (status IN ('PROPOSED', 'CONFIRMATION_PENDING', 'REGISTERED', 'REJECTED', 'WITHDRAWN')),
    expected_version          int NOT NULL CHECK (expected_version >= 0),
    authority_type            text NOT NULL CHECK (authority_type IN ('DEED', 'JUDGMENT', 'DECISION', 'SYNTHETIC_DEMO')),
    authority_reference       text NOT NULL CHECK (length(btrim(authority_reference)) BETWEEN 3 AND 200),
    reason                    text NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 2000),
    evidence_references       text[] NOT NULL CHECK (cardinality(evidence_references) BETWEEN 1 AND 50),
    evidence_versions         uuid[] NOT NULL DEFAULT '{}' CHECK (cardinality(evidence_versions) <= 50),
    completes_transaction_id  uuid,
    target_inscription_id     uuid REFERENCES inscription (id),
    area_m2                   numeric(14, 2) CHECK (area_m2 IS NULL OR area_m2 > 0),
    parcel_version_id         uuid REFERENCES parcel_version (id),
    proposed_by               uuid NOT NULL REFERENCES app_user (id),
    proposed_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
    confirmed_by              uuid REFERENCES app_user (id),
    registered_by             uuid REFERENCES app_user (id),
    registered_at             timestamptz,
    closed_by                 uuid REFERENCES app_user (id),
    closed_reason             text CHECK (closed_reason IS NULL OR length(btrim(closed_reason)) BETWEEN 10 AND 2000),
    previous_hash             bytea CHECK (previous_hash IS NULL OR octet_length(previous_hash) = 32),
    hash                      bytea CHECK (hash IS NULL OR octet_length(hash) = 32),
    demo                      boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_inscription_title CHECK ((kind = 'CREATION') = (new_title_id IS NOT NULL)
                                           AND (kind = 'CREATION' OR title_id IS NOT NULL)),
    CONSTRAINT ck_inscription_registered CHECK ((status = 'REGISTERED') = (number IS NOT NULL)
                                                AND (status = 'REGISTERED') = (hash IS NOT NULL)
                                                AND (status = 'REGISTERED') = (registered_by IS NOT NULL)
                                                AND (status = 'REGISTERED') = (registered_at IS NOT NULL)),
    CONSTRAINT ck_inscription_closed CHECK ((status IN ('REJECTED', 'WITHDRAWN')) = (closed_by IS NOT NULL)
                                            AND (status IN ('REJECTED', 'WITHDRAWN')) = (closed_reason IS NOT NULL))
);
-- Gap-free numbering per title: a number is taken only when the entry is registered, under the lock.
CREATE UNIQUE INDEX ux_inscription_number ON inscription (title_id, number) WHERE status = 'REGISTERED';
CREATE INDEX ix_inscription_title ON inscription (title_id, status);
CREATE INDEX ix_inscription_open ON inscription (office_id, proposed_at) WHERE status IN ('PROPOSED', 'CONFIRMATION_PENDING');

CREATE TABLE land_title (
    id                       uuid PRIMARY KEY,
    number                   text NOT NULL UNIQUE CHECK (number ~ '^DEMO-TF-[A-Z0-9]{2,16}-[0-9]{6}-[A-Z]$'),
    office_id                uuid NOT NULL REFERENCES office (id),
    -- Slice 2 creates synthetic titles only; the other origins arrive with their own migration.
    origin                   text NOT NULL CHECK (origin IN ('IMMATRICULATION', 'MORCELLEMENT', 'SYNTHETIC_DEMO')),
    creation_inscription_id  uuid NOT NULL UNIQUE REFERENCES inscription (id),
    created_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
    demo                     boolean NOT NULL DEFAULT true CHECK (demo),
    CONSTRAINT ck_land_title_synthetic_only CHECK (origin = 'SYNTHETIC_DEMO')
);
CREATE INDEX ix_land_title_office ON land_title (office_id, number);
ALTER TABLE inscription ADD CONSTRAINT fk_inscription_title FOREIGN KEY (title_id) REFERENCES land_title (id);

CREATE TABLE inscription_holder (
    inscription_id  uuid NOT NULL REFERENCES inscription (id),
    party_id        uuid NOT NULL REFERENCES register_party (id),
    interest        text NOT NULL CHECK (interest IN ('OWNERSHIP', 'CO_OWNERSHIP', 'USUFRUCT', 'BARE_OWNERSHIP')),
    share_num       int NOT NULL CHECK (share_num >= 1),
    share_den       int NOT NULL CHECK (share_den >= 1),
    CONSTRAINT ck_holder_share CHECK (share_num <= share_den),
    PRIMARY KEY (inscription_id, party_id, interest)
);

CREATE TABLE inscription_charge (
    inscription_id   uuid PRIMARY KEY REFERENCES inscription (id),
    charge_kind      text NOT NULL CHECK (charge_kind IN ('MORTGAGE', 'PRIVILEGE', 'SERVITUDE', 'OTHER_CHARGE',
                                                          'PRENOTATION', 'OPPOSITION', 'JUDICIAL_SEIZURE', 'ADMINISTRATIVE')),
    beneficiary_id   uuid REFERENCES register_party (id),
    amount           numeric(16, 2) CHECK (amount IS NULL OR amount > 0),
    blocks_transfer  boolean NOT NULL DEFAULT false,
    ends_on          date,
    note             text CHECK (note IS NULL OR length(btrim(note)) BETWEEN 3 AND 1000)
);

CREATE TABLE inscription_signature (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    inscription_id      uuid NOT NULL REFERENCES inscription (id),
    act                 text NOT NULL CHECK (act IN ('PROPOSED', 'CONFIRMED', 'REGISTERED')),
    signer_id           uuid NOT NULL REFERENCES app_user (id),
    canonical           bytea NOT NULL CHECK (octet_length(canonical) BETWEEN 1 AND 1048576),
    credential_id       text NOT NULL CHECK (length(credential_id) BETWEEN 1 AND 1000),
    public_key_cose     bytea NOT NULL CHECK (octet_length(public_key_cose) BETWEEN 1 AND 4096),
    nonce               bytea NOT NULL CHECK (octet_length(nonce) = 32),
    authenticator_data  bytea NOT NULL CHECK (octet_length(authenticator_data) BETWEEN 37 AND 4096),
    client_data         bytea NOT NULL CHECK (octet_length(client_data) BETWEEN 1 AND 8192),
    officer_signature   bytea NOT NULL CHECK (octet_length(officer_signature) BETWEEN 1 AND 4096),
    server_signature    text NOT NULL CHECK (server_signature ~ '^snigf:v[0-9]+:[A-Za-z0-9+/=]+$'),
    signed_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (inscription_id, act)
);

CREATE TABLE title_version (
    title_id           uuid NOT NULL REFERENCES land_title (id),
    version_no         int NOT NULL CHECK (version_no >= 1),
    inscription_id     uuid NOT NULL UNIQUE REFERENCES inscription (id),
    status             text NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    area_m2            numeric(14, 2),
    parcel_version_id  uuid REFERENCES parcel_version (id),
    holders            jsonb NOT NULL CHECK (jsonb_typeof(holders) = 'array'),
    charges            jsonb NOT NULL CHECK (jsonb_typeof(charges) = 'array'),
    restrictions       jsonb NOT NULL CHECK (jsonb_typeof(restrictions) = 'array'),
    PRIMARY KEY (title_id, version_no)
);

CREATE TABLE title_parcel (
    title_id           uuid NOT NULL,
    version_no         int NOT NULL,
    parcel_version_id  uuid NOT NULL REFERENCES parcel_version (id),
    PRIMARY KEY (title_id, version_no),
    FOREIGN KEY (title_id, version_no) REFERENCES title_version (title_id, version_no)
);

CREATE TABLE title_relation (
    parent_title_id        uuid NOT NULL REFERENCES land_title (id),
    child_title_id         uuid NOT NULL REFERENCES land_title (id),
    parent_inscription_id  uuid NOT NULL REFERENCES inscription (id),
    child_inscription_id   uuid NOT NULL REFERENCES inscription (id),
    PRIMARY KEY (parent_title_id, child_title_id),
    CHECK (parent_title_id <> child_title_id)
);

CREATE TABLE pending_transaction (
    id                    uuid PRIMARY KEY DEFAULT uuidv7(),
    title_id              uuid NOT NULL REFERENCES land_title (id),
    office_id             uuid NOT NULL REFERENCES office (id),
    kind                  text NOT NULL CHECK (kind IN ('SALE', 'GIFT', 'INHERITANCE')),
    channel               text NOT NULL CHECK (channel IN ('COUNTER', 'PROFESSIONAL_ACCOUNT')),
    depositor_party_id    uuid NOT NULL REFERENCES register_party (id),
    -- The notary who submitted (never an authenticated actor) and the officer who received.
    submitted_by_party_id uuid REFERENCES register_party (id),
    received_by           uuid NOT NULL REFERENCES app_user (id),
    application_id        uuid REFERENCES application (id),
    evidence_references   text[] NOT NULL CHECK (cardinality(evidence_references) BETWEEN 1 AND 50),
    status                text NOT NULL CHECK (status IN ('HOLDING', 'IN_CONFLICT', 'REGISTERED', 'WITHDRAWN', 'REFUSED')),
    deposited_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    review_due_at         timestamptz NOT NULL,
    overdue_recorded_at   timestamptz,
    closed_by             uuid REFERENCES app_user (id),
    closed_at             timestamptz,
    closed_reason         text CHECK (closed_reason IS NULL OR length(btrim(closed_reason)) BETWEEN 10 AND 2000),
    CONSTRAINT ck_transaction_due CHECK (review_due_at > deposited_at)
);
-- One holding transaction per title: the lock. A racing deposit waits on the title row, then conflicts.
CREATE UNIQUE INDEX ux_pending_transaction_holding ON pending_transaction (title_id) WHERE status = 'HOLDING';
CREATE INDEX ix_pending_transaction_office ON pending_transaction (office_id, status, review_due_at);
ALTER TABLE inscription ADD CONSTRAINT fk_inscription_transaction
    FOREIGN KEY (completes_transaction_id) REFERENCES pending_transaction (id);

CREATE TABLE transaction_conflict (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    title_id           uuid NOT NULL REFERENCES land_title (id),
    office_id          uuid NOT NULL REFERENCES office (id),
    holding_id         uuid NOT NULL REFERENCES pending_transaction (id),
    arriving_id        uuid NOT NULL UNIQUE REFERENCES pending_transaction (id),
    status             text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    opened_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    outcome            text CHECK (outcome IN ('KEEP_HOLDING', 'PREFER_ARRIVING', 'REFUSE_BOTH')),
    resolved_by        uuid REFERENCES app_user (id),
    resolved_at        timestamptz,
    resolution_reason  text CHECK (resolution_reason IS NULL OR length(btrim(resolution_reason)) BETWEEN 10 AND 2000),
    resolution_evidence text[] CHECK (resolution_evidence IS NULL OR cardinality(resolution_evidence) BETWEEN 1 AND 50),
    CHECK (holding_id <> arriving_id),
    CONSTRAINT ck_conflict_resolved CHECK ((status = 'RESOLVED') = (outcome IS NOT NULL)
                                           AND (status = 'RESOLVED') = (resolved_by IS NOT NULL)
                                           AND (status = 'RESOLVED') = (resolved_at IS NOT NULL)
                                           AND (status = 'RESOLVED') = (resolution_reason IS NOT NULL)
                                           AND (status = 'RESOLVED') = (resolution_evidence IS NOT NULL))
);
CREATE INDEX ix_transaction_conflict_title ON transaction_conflict (title_id, status);
CREATE INDEX ix_transaction_conflict_office ON transaction_conflict (office_id, status, opened_at);

-- Modelled for a future, separately decided migration of real paper titles (ADR-040 §9).
CREATE TABLE register_migration_batch (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    origin        text NOT NULL CHECK (origin = 'SYNTHETIC_DEMO'),
    provenance    text NOT NULL,
    verified_by   uuid REFERENCES app_user (id),
    approved_by   uuid REFERENCES app_user (id),
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- --- Guards: what was recorded never changes, for anyone ---------------------------------------------

CREATE FUNCTION inscription_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.number IS NOT NULL OR NEW.hash IS NOT NULL THEN
            RAISE EXCEPTION 'an inscription is first proposed' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('PROPOSED', 'CONFIRMATION_PENDING') THEN
        RAISE EXCEPTION 'inscription % is final: it never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.new_title_id, NEW.office_id, NEW.kind, NEW.expected_version, NEW.authority_type, NEW.authority_reference,
        NEW.reason, NEW.evidence_references, NEW.evidence_versions, NEW.completes_transaction_id, NEW.target_inscription_id,
        NEW.area_m2, NEW.parcel_version_id, NEW.proposed_by, NEW.proposed_at, NEW.demo)
       IS DISTINCT FROM
       (OLD.id, OLD.new_title_id, OLD.office_id, OLD.kind, OLD.expected_version, OLD.authority_type, OLD.authority_reference,
        OLD.reason, OLD.evidence_references, OLD.evidence_versions, OLD.completes_transaction_id, OLD.target_inscription_id,
        OLD.area_m2, OLD.parcel_version_id, OLD.proposed_by, OLD.proposed_at, OLD.demo) THEN
        RAISE EXCEPTION 'what an inscription says never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.title_id IS DISTINCT FROM OLD.title_id AND NOT (OLD.title_id IS NULL AND NEW.title_id = OLD.new_title_id) THEN
        RAISE EXCEPTION 'an inscription stays on its title' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('CONFIRMATION_PENDING', 'REGISTERED', 'REJECTED', 'WITHDRAWN'))
            OR (OLD.status = 'CONFIRMATION_PENDING' AND NEW.status IN ('REGISTERED', 'REJECTED', 'WITHDRAWN'))) THEN
        RAISE EXCEPTION 'an inscription moves only forward' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status = 'REGISTERED' AND NOT EXISTS (SELECT 1 FROM inscription_signature s
                                                  WHERE s.inscription_id = NEW.id AND s.act IN ('CONFIRMED', 'REGISTERED')) THEN
        RAISE EXCEPTION 'nothing is registered unsigned' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inscription_guard BEFORE INSERT OR UPDATE ON inscription
    FOR EACH ROW EXECUTE FUNCTION inscription_guard();
CREATE TRIGGER inscription_no_delete BEFORE DELETE ON inscription
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER inscription_no_truncate BEFORE TRUNCATE ON inscription
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Holders and charges are written with their proposal, never later.
CREATE FUNCTION inscription_child_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM inscription i WHERE i.id = NEW.inscription_id AND i.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'holders and charges are written with a proposal' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inscription_holder_guard BEFORE INSERT ON inscription_holder
    FOR EACH ROW EXECUTE FUNCTION inscription_child_guard();
CREATE TRIGGER inscription_charge_guard BEFORE INSERT ON inscription_charge
    FOR EACH ROW EXECUTE FUNCTION inscription_child_guard();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['register_party', 'land_title', 'inscription_holder', 'inscription_charge', 'inscription_signature',
                             'title_version', 'title_parcel', 'title_relation', 'register_migration_batch'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_modification()',
                       t || '_append_only', t);
        EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_modification()',
                       t || '_no_truncate', t);
    END LOOP;
END;
$$;

CREATE FUNCTION pending_transaction_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.id, NEW.title_id, NEW.office_id, NEW.kind, NEW.channel, NEW.depositor_party_id, NEW.submitted_by_party_id, NEW.received_by,
        NEW.application_id, NEW.evidence_references, NEW.deposited_at, NEW.review_due_at)
       IS DISTINCT FROM
       (OLD.id, OLD.title_id, OLD.office_id, OLD.kind, OLD.channel, OLD.depositor_party_id, OLD.submitted_by_party_id, OLD.received_by,
        OLD.application_id, OLD.evidence_references, OLD.deposited_at, OLD.review_due_at) THEN
        RAISE EXCEPTION 'what was deposited never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        RAISE EXCEPTION 'transaction % is final', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT ((OLD.status = 'HOLDING' AND NEW.status IN ('REGISTERED', 'WITHDRAWN', 'REFUSED'))
                OR (OLD.status = 'IN_CONFLICT' AND NEW.status IN ('HOLDING', 'WITHDRAWN', 'REFUSED'))) THEN
        RAISE EXCEPTION 'a transaction moves only forward' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.overdue_recorded_at IS NOT NULL AND NEW.overdue_recorded_at IS DISTINCT FROM OLD.overdue_recorded_at THEN
        RAISE EXCEPTION 'an overdue lock is recorded once' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER pending_transaction_guard BEFORE UPDATE ON pending_transaction
    FOR EACH ROW EXECUTE FUNCTION pending_transaction_guard();
CREATE TRIGGER pending_transaction_no_delete BEFORE DELETE ON pending_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER pending_transaction_no_truncate BEFORE TRUNCATE ON pending_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

CREATE FUNCTION transaction_conflict_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF (NEW.id, NEW.title_id, NEW.office_id, NEW.holding_id, NEW.arriving_id, NEW.opened_at)
       IS DISTINCT FROM (OLD.id, OLD.title_id, OLD.office_id, OLD.holding_id, OLD.arriving_id, OLD.opened_at) THEN
        RAISE EXCEPTION 'what a conflict is about never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'OPEN' OR NEW.status <> 'RESOLVED' THEN
        RAISE EXCEPTION 'a conflict is resolved once' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transaction_conflict_guard BEFORE UPDATE ON transaction_conflict
    FOR EACH ROW EXECUTE FUNCTION transaction_conflict_guard();
CREATE TRIGGER transaction_conflict_no_delete BEFORE DELETE ON transaction_conflict
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER transaction_conflict_no_truncate BEFORE TRUNCATE ON transaction_conflict
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Helpers ---------------------------------------------------------------------------------------

CREATE FUNCTION register_refuse(p_code text) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '%', p_code USING ERRCODE = 'RG001';
END;
$$;

-- The canonical encoding (org.snigf.mvp.records.CanonicalForm): length-prefixed, -1 when absent.
CREATE FUNCTION canonical_field(p_value bytea) RETURNS bytea
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT CASE WHEN p_value IS NULL THEN int4send(-1) ELSE int4send(octet_length(p_value)) || p_value END
$$;

CREATE FUNCTION canonical_text(p_value text) RETURNS bytea
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT canonical_field(convert_to(p_value, 'UTF8'))
$$;

-- The current version number of a title, 0 when it has none.
CREATE FUNCTION title_current_version(p_title uuid) RETURNS int
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT coalesce(max(version_no), 0) FROM title_version WHERE title_id = p_title
$$;

-- snigf-inscription-v1: every field, holders and charges included. It names the title version it was
-- prepared against and that version's chain hash, so it does not change between the signatures.
CREATE FUNCTION inscription_canonical(p_inscription uuid) RETURNS bytea
LANGUAGE plpgsql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    i        inscription;
    previous bytea;
    encoded  bytea;
    h        record;
    c        inscription_charge;
    v        text;
    u        uuid;
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription;
    SELECT p.hash INTO previous FROM inscription p
     WHERE p.title_id = i.title_id AND p.status = 'REGISTERED' AND p.number = i.expected_version;
    encoded := canonical_text('snigf-inscription-v1')
        || canonical_text(i.id::text)
        || canonical_text(coalesce(i.title_id, i.new_title_id)::text)
        || canonical_text(i.office_id::text)
        || canonical_text(i.kind)
        || canonical_text(i.expected_version::text)
        || canonical_field(previous)
        || canonical_text(i.authority_type)
        || canonical_text(i.authority_reference)
        || canonical_text(i.reason)
        || canonical_text(cardinality(i.evidence_references)::text);
    FOREACH v IN ARRAY i.evidence_references LOOP
        encoded := encoded || canonical_text(v);
    END LOOP;
    encoded := encoded || canonical_text(cardinality(i.evidence_versions)::text);
    FOREACH u IN ARRAY i.evidence_versions LOOP
        encoded := encoded || canonical_text(u::text);
    END LOOP;
    encoded := encoded
        || canonical_text(i.completes_transaction_id::text)
        || canonical_text(i.target_inscription_id::text)
        || canonical_text(i.area_m2::text)
        || canonical_text(i.parcel_version_id::text)
        || canonical_text(i.proposed_by::text)
        || canonical_text((SELECT count(*) FROM inscription_holder ih WHERE ih.inscription_id = i.id)::text);
    FOR h IN SELECT party_id, interest, share_num, share_den FROM inscription_holder ih
              WHERE ih.inscription_id = i.id ORDER BY party_id, interest LOOP
        encoded := encoded || canonical_text(h.party_id::text) || canonical_text(h.interest)
                   || canonical_text(h.share_num::text) || canonical_text(h.share_den::text);
    END LOOP;
    SELECT * INTO c FROM inscription_charge ic WHERE ic.inscription_id = i.id;
    encoded := encoded
        || canonical_text(c.charge_kind)
        || canonical_text(c.beneficiary_id::text)
        || canonical_text(c.amount::text)
        || canonical_text(CASE WHEN c.inscription_id IS NULL THEN NULL WHEN c.blocks_transfer THEN 'true' ELSE 'false' END)
        || canonical_text(c.ends_on::text)
        || canonical_text(c.note)
        || canonical_text('demo');
    RETURN encoded;
END;
$$;

-- The state of a title after version p_version, computed only from the registered inscriptions:
-- cancelled inscriptions have no effect, corrected ones count with their latest correction's content.
CREATE FUNCTION title_state(p_title uuid, p_version int) RETURNS jsonb
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

-- The actor holds an interest in the title, or would gain one by this inscription.
CREATE FUNCTION register_actor_interested(p_actor uuid, p_title uuid, p_inscription uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM title_version v
          CROSS JOIN LATERAL (SELECT x->>'party' AS party FROM jsonb_array_elements(v.holders) x
                              UNION ALL SELECT x->>'beneficiary' FROM jsonb_array_elements(v.charges) x
                              UNION ALL SELECT x->>'beneficiary' FROM jsonb_array_elements(v.restrictions) x) p
          JOIN register_party rp ON rp.id::text = p.party
         WHERE v.title_id = p_title AND v.version_no = title_current_version(p_title) AND rp.account_id = p_actor)
    OR EXISTS (SELECT 1 FROM inscription_holder h JOIN register_party rp ON rp.id = h.party_id
                WHERE h.inscription_id = p_inscription AND rp.account_id = p_actor)
    OR EXISTS (SELECT 1 FROM inscription_charge c JOIN register_party rp ON rp.id = c.beneficiary_id
                WHERE c.inscription_id = p_inscription AND rp.account_id = p_actor)
$$;

-- The office code of a register record, for the endpoints' permission checks (never shown).
CREATE FUNCTION register_office_code(p_type text, p_id uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT o.code FROM office o WHERE o.id = CASE p_type
        WHEN 'TITLE' THEN (SELECT office_id FROM land_title WHERE id = p_id)
        WHEN 'INSCRIPTION' THEN (SELECT office_id FROM inscription WHERE id = p_id)
        WHEN 'TRANSACTION' THEN (SELECT office_id FROM pending_transaction WHERE id = p_id)
        WHEN 'CONFLICT' THEN (SELECT office_id FROM transaction_conflict WHERE id = p_id)
    END
$$;

-- --- Parties and proposals ---------------------------------------------------------------------------

CREATE FUNCTION register_party_create(p_office uuid, p_kind text, p_name text, p_identity_kind text, p_identity_number text,
                                      p_chamber text, p_registration text, p_jurisdiction text, p_account uuid) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    new_id uuid;
BEGIN
    IF NOT holds_at(current_actor(), 'register.propose', p_office) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    INSERT INTO register_party (office_id, kind, full_name, identity_kind, identity_number, chamber, registration_number,
                                jurisdiction, account_id, created_by)
    VALUES (p_office, p_kind, btrim(p_name), p_identity_kind, btrim(p_identity_number), p_chamber, p_registration, p_jurisdiction,
            p_account, current_actor())
    RETURNING id INTO new_id;
    RETURN new_id;
END;
$$;

-- Exact fractions: full ownership (OWNERSHIP, CO_OWNERSHIP) totals one, or usufruct and bare ownership
-- each total one, together.
CREATE FUNCTION holder_shares_total_one(p_holders jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    grp          text;
    num          numeric;
    den          numeric;
    h            record;
    found_groups text[] := '{}';
BEGIN
    FOREACH grp IN ARRAY ARRAY['FULL', 'USUFRUCT', 'BARE_OWNERSHIP'] LOOP
        num := 0;
        den := 1;
        FOR h IN SELECT x."shareNum" AS n, x."shareDen" AS d
                   FROM jsonb_to_recordset(p_holders) AS x(interest text, "shareNum" int, "shareDen" int)
                  WHERE CASE WHEN x.interest IN ('OWNERSHIP', 'CO_OWNERSHIP') THEN 'FULL' ELSE x.interest END = grp LOOP
            num := num * h.d + h.n * den;
            den := den * h.d;
        END LOOP;
        IF num <> 0 THEN
            IF num <> den THEN
                RETURN false;
            END IF;
            found_groups := found_groups || grp;
        END IF;
    END LOOP;
    RETURN found_groups = ARRAY['FULL'] OR found_groups = ARRAY['USUFRUCT', 'BARE_OWNERSHIP'];
END;
$$;

-- p_holders: [{"party","interest","shareNum","shareDen"}]; p_charge: {"kind","beneficiary","amount",
-- "blocksTransfer","endsOn","note"}. Each interest group sums to exactly one.
CREATE FUNCTION register_propose(p_office uuid, p_title uuid, p_kind text, p_expected int, p_authority_type text, p_authority_reference text,
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
        IF NOT holder_shares_total_one(p_holders) THEN
            PERFORM register_refuse('SHARES_MUST_TOTAL_ONE');
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
        INSERT INTO inscription_holder (inscription_id, party_id, interest, share_num, share_den)
        SELECT new_id, h.party, h.interest, h."shareNum", h."shareDen"
          FROM jsonb_to_recordset(p_holders) AS h(party uuid, interest text, "shareNum" int, "shareDen" int);
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

-- The bytes an officer signs, for whoever may read the inscription.
CREATE FUNCTION register_canonical(p_inscription uuid) RETURNS bytea
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    office uuid;
BEGIN
    SELECT office_id INTO office FROM inscription WHERE id = p_inscription;
    IF office IS NULL OR NOT holds_at(current_actor(), 'register.read', office) THEN
        PERFORM register_refuse('INSCRIPTION_NOT_FOUND');
    END IF;
    RETURN inscription_canonical(p_inscription);
END;
$$;

-- Stores one signing act. The signature itself was verified by the application against the
-- officer's key; this checks what the database can on its own: the bytes signed are exactly the
-- inscription's, the challenge answers them, and the key is the actor's own, registered now.
CREATE FUNCTION register_store_signature(p_inscription uuid, p_act text, p_actor uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    nonce   bytea := decode(p_signature->>'nonce', 'base64');
    client  bytea := decode(p_signature->>'clientData', 'base64');
    pub_key bytea := decode(p_signature->>'publicKey', 'base64');
    wanted  text;
BEGIN
    IF p_canonical IS DISTINCT FROM inscription_canonical(p_inscription) THEN
        PERFORM register_refuse('CANONICAL_MISMATCH');
    END IF;
    wanted := rtrim(translate(encode(sha256(p_canonical || nonce), 'base64'), '+/', '-_'), '=');
    IF rtrim(convert_from(client, 'UTF8')::jsonb->>'challenge', '=') IS DISTINCT FROM wanted THEN
        PERFORM register_refuse('SIGNATURE_CHALLENGE_MISMATCH');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM user_credentials c JOIN user_entities e ON e.id = c.user_entity_user_id
                    WHERE c.credential_id = p_signature->>'credentialId' AND e.app_user_id = p_actor AND c.public_key = pub_key) THEN
        PERFORM register_refuse('SIGNATURE_NOT_OWN_KEY');
    END IF;
    INSERT INTO inscription_signature (inscription_id, act, signer_id, canonical, credential_id, public_key_cose, nonce,
                                       authenticator_data, client_data, officer_signature, server_signature)
    VALUES (p_inscription, p_act, p_actor, p_canonical, p_signature->>'credentialId', pub_key, nonce,
            decode(p_signature->>'authenticatorData', 'base64'), client, decode(p_signature->>'officerSignature', 'base64'),
            p_signature->>'serverSignature');
END;
$$;

-- Everything a registration requires, then the registered row, the title and the derived version.
CREATE FUNCTION register_apply(p_inscription uuid, p_actor uuid) RETURNS void
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
        INSERT INTO land_title (id, number, office_id, origin, creation_inscription_id)
        SELECT title,
               format('DEMO-TF-%s-%s-%s', o.code, lpad(q.n::text, 6, '0'), substr('ABCDEFGHJKMNPQRSTVWXYZ', 1 + (q.n % 22)::int, 1)),
               i.office_id, 'SYNTHETIC_DEMO', i.id
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

-- The common checks of a signing act: the row locked in title order, the permission, the version.
CREATE FUNCTION register_lock(p_inscription uuid, p_permission text, p_actor uuid) RETURNS inscription
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    i inscription;
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription;
    IF i.id IS NULL OR NOT holds_at(p_actor, 'register.read', i.office_id) THEN
        PERFORM register_refuse('INSCRIPTION_NOT_FOUND');
    END IF;
    IF i.title_id IS NOT NULL THEN
        PERFORM 1 FROM land_title WHERE id = i.title_id FOR UPDATE;
    END IF;
    SELECT * INTO i FROM inscription WHERE id = p_inscription FOR UPDATE;
    IF NOT holds_at(p_actor, p_permission, i.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF i.status NOT IN ('PROPOSED', 'CONFIRMATION_PENDING') THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    IF title_current_version(i.title_id) <> i.expected_version THEN
        PERFORM register_refuse('TITLE_CHANGED');
    END IF;
    IF register_actor_interested(p_actor, i.title_id, i.id) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    RETURN i;
END;
$$;

-- A kind under single control: one conservator's signature registers it.
CREATE FUNCTION register_register(p_inscription uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    i     inscription := register_lock(p_inscription, 'register.inscribe', actor);
BEGIN
    IF i.kind IN ('CLOSURE', 'CANCELLATION', 'CORRECTION') THEN
        PERFORM register_refuse('DUAL_CONTROL_REQUIRED');
    END IF;
    IF i.status <> 'PROPOSED' THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    PERFORM register_store_signature(p_inscription, 'REGISTERED', actor, p_canonical, p_signature);
    PERFORM register_apply(p_inscription, actor);
END;
$$;

-- Dual control, first signature: the proposer signs and asks for a second conservator.
CREATE FUNCTION register_request_confirmation(p_inscription uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    i     inscription := register_lock(p_inscription, 'register.propose', actor);
BEGIN
    IF i.kind NOT IN ('CLOSURE', 'CANCELLATION', 'CORRECTION') THEN
        PERFORM register_refuse('DUAL_CONTROL_NOT_APPLICABLE');
    END IF;
    IF i.status <> 'PROPOSED' THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    IF i.proposed_by <> actor THEN
        PERFORM register_refuse('NOT_THE_PROPOSER');
    END IF;
    PERFORM register_store_signature(p_inscription, 'PROPOSED', actor, p_canonical, p_signature);
    UPDATE inscription SET status = 'CONFIRMATION_PENDING' WHERE id = p_inscription;
END;
$$;

-- Dual control, second signature: another conservator confirms, and it is registered.
CREATE FUNCTION register_confirm(p_inscription uuid, p_canonical bytea, p_signature jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    i     inscription := register_lock(p_inscription, 'register.confirm', actor);
BEGIN
    IF i.status <> 'CONFIRMATION_PENDING' THEN
        PERFORM register_refuse('NOT_PENDING_CONFIRMATION');
    END IF;
    IF i.proposed_by = actor THEN
        PERFORM register_refuse('SELF_CONFIRMATION');
    END IF;
    PERFORM register_store_signature(p_inscription, 'CONFIRMED', actor, p_canonical, p_signature);
    PERFORM register_apply(p_inscription, actor);
END;
$$;

-- A proposal that will not be registered: withdrawn by its proposer, or rejected by a conservator.
CREATE FUNCTION register_close_proposal(p_inscription uuid, p_outcome text, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    i     inscription;
BEGIN
    SELECT * INTO i FROM inscription WHERE id = p_inscription FOR UPDATE;
    IF i.id IS NULL OR NOT holds_at(actor, 'register.read', i.office_id) THEN
        PERFORM register_refuse('INSCRIPTION_NOT_FOUND');
    END IF;
    IF i.status NOT IN ('PROPOSED', 'CONFIRMATION_PENDING') THEN
        PERFORM register_refuse('INSCRIPTION_CLOSED');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('WITHDRAWN', 'REJECTED') THEN
        PERFORM register_refuse('OUTCOME_REQUIRED');
    END IF;
    IF p_outcome = 'WITHDRAWN' AND i.proposed_by <> actor THEN
        PERFORM register_refuse('NOT_THE_PROPOSER');
    END IF;
    IF p_outcome = 'REJECTED' AND NOT (holds_at(actor, 'register.inscribe', i.office_id) OR holds_at(actor, 'register.confirm', i.office_id)) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    UPDATE inscription SET status = p_outcome, closed_by = actor, closed_reason = btrim(p_reason) WHERE id = p_inscription;
END;
$$;

-- --- Deposits and conflicts ---------------------------------------------------------------------------

-- A second deposit is recorded, never refused and never queued (ADR-040 §4).
CREATE FUNCTION transaction_deposit(p_title uuid, p_kind text, p_depositor uuid, p_submitted_by uuid, p_evidence text[],
                                    p_review_after interval) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor   uuid := current_actor();
    title   land_title;
    holding uuid;
    new_id  uuid;
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
    SELECT id INTO holding FROM pending_transaction WHERE title_id = p_title AND status = 'HOLDING';
    -- With a lock freed but a conflict still open, a new deposit waits for the same decision.
    IF holding IS NULL THEN
        SELECT holding_id INTO holding FROM transaction_conflict WHERE title_id = p_title AND status = 'OPEN' ORDER BY opened_at LIMIT 1;
    END IF;
    INSERT INTO pending_transaction (title_id, office_id, kind, channel, depositor_party_id, submitted_by_party_id, received_by,
                                     evidence_references, status, review_due_at)
    VALUES (p_title, title.office_id, p_kind, 'COUNTER', p_depositor, p_submitted_by, actor, p_evidence,
            CASE WHEN holding IS NULL THEN 'HOLDING' ELSE 'IN_CONFLICT' END, clock_timestamp() + p_review_after)
    RETURNING id INTO new_id;
    IF holding IS NOT NULL THEN
        INSERT INTO transaction_conflict (title_id, office_id, holding_id, arriving_id) VALUES (p_title, title.office_id, holding, new_id);
    END IF;
    RETURN new_id;
END;
$$;

-- Withdrawn at the depositor's request, or refused with a reason. No other deposit becomes valid by
-- it: an open conflict stays open until it is decided.
CREATE FUNCTION transaction_close(p_transaction uuid, p_outcome text, p_reason text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor uuid := current_actor();
    t     pending_transaction;
BEGIN
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction;
    IF t.id IS NULL OR NOT holds_at(actor, 'register.read', t.office_id) THEN
        PERFORM register_refuse('TRANSACTION_NOT_FOUND');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('WITHDRAWN', 'REFUSED') THEN
        PERFORM register_refuse('OUTCOME_REQUIRED');
    END IF;
    PERFORM 1 FROM land_title WHERE id = t.title_id FOR UPDATE;
    SELECT * INTO t FROM pending_transaction WHERE id = p_transaction FOR UPDATE;
    IF NOT holds_at(actor, CASE p_outcome WHEN 'REFUSED' THEN 'register.transaction.decide' ELSE 'register.propose' END, t.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF t.status NOT IN ('HOLDING', 'IN_CONFLICT') THEN
        PERFORM register_refuse('TRANSACTION_CLOSED');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id = t.depositor_party_id AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    UPDATE pending_transaction SET status = p_outcome, closed_by = actor, closed_at = clock_timestamp(), closed_reason = btrim(p_reason)
     WHERE id = p_transaction;
    -- A proposal that would have completed it can no longer be registered.
    UPDATE inscription SET status = 'REJECTED', closed_by = actor, closed_reason = left('The deposit it completes was closed: ' || btrim(p_reason), 2000)
     WHERE completes_transaction_id = p_transaction AND status IN ('PROPOSED', 'CONFIRMATION_PENDING');
END;
$$;

-- The authorised decision on a conflict, by a conservator who received neither deposit.
CREATE FUNCTION conflict_resolve(p_conflict uuid, p_outcome text, p_reason text, p_evidence text[]) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    actor    uuid := current_actor();
    c        transaction_conflict;
    holding  pending_transaction;
    arriving pending_transaction;
BEGIN
    SELECT * INTO c FROM transaction_conflict WHERE id = p_conflict;
    IF c.id IS NULL OR NOT holds_at(actor, 'register.read', c.office_id) THEN
        PERFORM register_refuse('CONFLICT_NOT_FOUND');
    END IF;
    PERFORM 1 FROM land_title WHERE id = c.title_id FOR UPDATE;
    SELECT * INTO c FROM transaction_conflict WHERE id = p_conflict FOR UPDATE;
    IF NOT holds_at(actor, 'register.transaction.decide', c.office_id) THEN
        PERFORM register_refuse('REGISTER_FORBIDDEN');
    END IF;
    IF c.status <> 'OPEN' THEN
        PERFORM register_refuse('CONFLICT_CLOSED');
    END IF;
    SELECT * INTO holding FROM pending_transaction WHERE id = c.holding_id FOR UPDATE;
    SELECT * INTO arriving FROM pending_transaction WHERE id = c.arriving_id FOR UPDATE;
    IF actor IN (holding.received_by, arriving.received_by) THEN
        PERFORM register_refuse('RECEIVER_CANNOT_DECIDE');
    END IF;
    IF EXISTS (SELECT 1 FROM register_party rp WHERE rp.id IN (holding.depositor_party_id, arriving.depositor_party_id) AND rp.account_id = actor) THEN
        PERFORM register_refuse('ACTOR_HAS_INTEREST');
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('KEEP_HOLDING', 'PREFER_ARRIVING', 'REFUSE_BOTH') THEN
        PERFORM register_refuse('OUTCOME_REQUIRED');
    END IF;
    IF p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 10 AND 2000 OR coalesce(cardinality(p_evidence), 0) = 0 THEN
        PERFORM register_refuse('REASON_REQUIRED');
    END IF;
    IF p_outcome = 'KEEP_HOLDING' AND holding.status <> 'HOLDING' THEN
        PERFORM register_refuse('HOLDING_NOT_IN_FORCE');
    END IF;
    IF p_outcome = 'PREFER_ARRIVING' AND EXISTS (SELECT 1 FROM transaction_conflict o WHERE o.title_id = c.title_id AND o.status = 'OPEN' AND o.id <> c.id) THEN
        PERFORM register_refuse('OTHER_CONFLICTS_OPEN');
    END IF;
    IF p_outcome IN ('PREFER_ARRIVING', 'REFUSE_BOTH') AND holding.status = 'HOLDING' THEN
        UPDATE pending_transaction SET status = 'REFUSED', closed_by = actor, closed_at = clock_timestamp(), closed_reason = btrim(p_reason) WHERE id = holding.id;
        UPDATE inscription SET status = 'REJECTED', closed_by = actor, closed_reason = left('The deposit it completes was refused: ' || btrim(p_reason), 2000)
         WHERE completes_transaction_id = holding.id AND status IN ('PROPOSED', 'CONFIRMATION_PENDING');
    END IF;
    IF arriving.status = 'IN_CONFLICT' THEN
        UPDATE pending_transaction
           SET status = CASE p_outcome WHEN 'PREFER_ARRIVING' THEN 'HOLDING' ELSE 'REFUSED' END,
               closed_by = CASE p_outcome WHEN 'PREFER_ARRIVING' THEN NULL ELSE actor END,
               closed_at = CASE p_outcome WHEN 'PREFER_ARRIVING' THEN NULL ELSE clock_timestamp() END,
               closed_reason = CASE p_outcome WHEN 'PREFER_ARRIVING' THEN NULL ELSE btrim(p_reason) END
         WHERE id = arriving.id;
    ELSIF p_outcome = 'PREFER_ARRIVING' THEN
        PERFORM register_refuse('ARRIVING_NOT_IN_FORCE');
    END IF;
    UPDATE transaction_conflict SET status = 'RESOLVED', outcome = p_outcome, resolved_by = actor, resolved_at = clock_timestamp(),
                                    resolution_reason = btrim(p_reason), resolution_evidence = p_evidence
     WHERE id = p_conflict;
END;
$$;

-- Locks past their review date, each recorded once; nothing expires and nothing is released.
CREATE FUNCTION register_record_overdue_locks() RETURNS TABLE (transaction_id uuid, title_id uuid, due_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    RETURN QUERY
    UPDATE pending_transaction t SET overdue_recorded_at = clock_timestamp()
     WHERE t.status = 'HOLDING' AND t.review_due_at <= clock_timestamp() AND t.overdue_recorded_at IS NULL
    RETURNING t.id, t.title_id, t.review_due_at;
END;
$$;

-- Recomputes every version of a title from its inscriptions and compares it with what was written.
CREATE FUNCTION title_versions_check(p_title uuid) RETURNS TABLE (version_no int, matches boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    office uuid;
BEGIN
    SELECT office_id INTO office FROM land_title WHERE id = p_title;
    IF office IS NULL OR NOT holds_at(current_actor(), 'register.read', office) THEN
        PERFORM register_refuse('TITLE_NOT_FOUND');
    END IF;
    RETURN QUERY
    SELECT v.version_no,
           jsonb_build_object('status', v.status, 'areaM2', v.area_m2, 'parcelVersion', v.parcel_version_id,
                              'holders', v.holders, 'charges', v.charges, 'restrictions', v.restrictions)
           = title_state(p_title, v.version_no)
      FROM title_version v WHERE v.title_id = p_title ORDER BY v.version_no;
END;
$$;

-- --- The audit subjects: register events belong to their title, at its conservation ---------------------

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
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.id, t.office_id)) FROM land_title t WHERE t.id = p_id)
        WHEN p_type = 'INSCRIPTION' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', coalesce(i.title_id, i.new_title_id), i.office_id))
               FROM inscription i WHERE i.id = p_id)
        WHEN p_type = 'PENDING_TRANSACTION' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', t.title_id, t.office_id)) FROM pending_transaction t WHERE t.id = p_id)
        WHEN p_type = 'TRANSACTION_CONFLICT' THEN
            (SELECT jsonb_build_array(audit_subject('LAND_TITLE', c.title_id, c.office_id)) FROM transaction_conflict c WHERE c.id = p_id)
        ELSE NULL
    END
$$;

-- --- Row-level security: the register of a conservation, for those who read it there ----------------------

ALTER TABLE register_party ENABLE ROW LEVEL SECURITY;
ALTER TABLE land_title ENABLE ROW LEVEL SECURITY;
ALTER TABLE inscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE inscription_holder ENABLE ROW LEVEL SECURITY;
ALTER TABLE inscription_charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE inscription_signature ENABLE ROW LEVEL SECURITY;
ALTER TABLE title_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE title_parcel ENABLE ROW LEVEL SECURITY;
ALTER TABLE title_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE pending_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_conflict ENABLE ROW LEVEL SECURITY;
ALTER TABLE register_migration_batch ENABLE ROW LEVEL SECURITY;

CREATE POLICY register_party_read ON register_party FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));
CREATE POLICY land_title_read ON land_title FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));
CREATE POLICY inscription_read ON inscription FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));
CREATE POLICY inscription_holder_read ON inscription_holder FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM inscription i WHERE i.id = inscription_id));
CREATE POLICY inscription_charge_read ON inscription_charge FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM inscription i WHERE i.id = inscription_id));
CREATE POLICY inscription_signature_read ON inscription_signature FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM inscription i WHERE i.id = inscription_id));
CREATE POLICY title_version_read ON title_version FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM land_title t WHERE t.id = title_id));
CREATE POLICY title_parcel_read ON title_parcel FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM land_title t WHERE t.id = title_id));
CREATE POLICY title_relation_read ON title_relation FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM land_title t WHERE t.id = parent_title_id));
CREATE POLICY pending_transaction_read ON pending_transaction FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));
CREATE POLICY transaction_conflict_read ON transaction_conflict FOR SELECT TO snigf_app
    USING (holds_at(current_actor(), 'register.read', office_id));

-- --- Grants: reading, and the functions; never a direct write ------------------------------------------------

REVOKE ALL ON register_party, land_title, inscription, inscription_holder, inscription_charge, inscription_signature, title_version,
              title_parcel, title_relation, pending_transaction, transaction_conflict, register_migration_batch FROM PUBLIC;
GRANT SELECT ON register_party, land_title, inscription, inscription_holder, inscription_charge, inscription_signature, title_version,
                title_parcel, title_relation, pending_transaction, transaction_conflict TO snigf_app;

REVOKE EXECUTE ON FUNCTION register_refuse(text), holder_shares_total_one(jsonb), inscription_canonical(uuid), title_state(uuid, int),
                           register_actor_interested(uuid, uuid, uuid), register_store_signature(uuid, text, uuid, bytea, jsonb),
                           register_apply(uuid, uuid), register_lock(uuid, text, uuid), title_current_version(uuid),
                           canonical_field(bytea), canonical_text(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION register_office_code(text, uuid),
                           register_party_create(uuid, text, text, text, text, text, text, text, uuid),
                           register_propose(uuid, uuid, text, int, text, text, text, text[], uuid[], uuid, uuid, numeric, uuid, jsonb, jsonb),
                           register_canonical(uuid), register_register(uuid, bytea, jsonb),
                           register_request_confirmation(uuid, bytea, jsonb), register_confirm(uuid, bytea, jsonb),
                           register_close_proposal(uuid, text, text), transaction_deposit(uuid, text, uuid, uuid, text[], interval),
                           transaction_close(uuid, text, text), conflict_resolve(uuid, text, text, text[]),
                           register_record_overdue_locks(), title_versions_check(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION register_office_code(text, uuid),
                          register_party_create(uuid, text, text, text, text, text, text, text, uuid),
                          register_propose(uuid, uuid, text, int, text, text, text, text[], uuid[], uuid, uuid, numeric, uuid, jsonb, jsonb),
                          register_canonical(uuid), register_register(uuid, bytea, jsonb),
                          register_request_confirmation(uuid, bytea, jsonb), register_confirm(uuid, bytea, jsonb),
                          register_close_proposal(uuid, text, text), transaction_deposit(uuid, text, uuid, uuid, text[], interval),
                          transaction_close(uuid, text, text), conflict_resolve(uuid, text, text, text[]),
                          register_record_overdue_locks(), title_versions_check(uuid) TO snigf_app;
