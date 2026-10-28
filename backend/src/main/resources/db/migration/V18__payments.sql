-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V18: simulated payment and reconciliation (MVP-08, E-17, ADR-037). No real money moves.
--
--   payment_policy                 one row: the gate before acceptance, and the only approved
--                                  provider (DEMO_SIMULATOR); written at start-up from settings
--   fee_schedule                   DEMO fees per application type, versions that never overlap
--   payment_request                what an applicant owes for an application: DEMO-PAY reference,
--                                  amount copied from the fee in force at submission, expiry
--   payment_record                 every authenticated provider report, append-only, one per
--                                  provider transaction id, with the database's outcome
--   payment_exception_resolution   the finance officer's closing of an exception
--
-- One function decides every report (payment_callback_apply) under a lock on the request, so
-- duplicates, concurrent copies and expiry cannot race; expiry is decided by expires_at, never by
-- whether the sweep has run. Money reported taken without a pending request of the same amount
-- is an exception, never a payment. The status of a request changes only inside these functions.

SET search_path = snigf, public;

-- --- Policy ------------------------------------------------------------------------------------

CREATE TABLE payment_policy (
    id                      boolean PRIMARY KEY DEFAULT true CHECK (id),
    gate_before_acceptance  boolean NOT NULL,
    -- A real provider stays disabled until an ADR approves it and a migration widens this.
    provider                text NOT NULL CHECK (provider = 'DEMO_SIMULATOR'),
    updated_at              timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO payment_policy (gate_before_acceptance, provider) VALUES (true, 'DEMO_SIMULATOR');

-- Applies the gate setting; true when it changed.
CREATE FUNCTION payment_policy_apply(p_gate boolean) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    changed boolean;
BEGIN
    UPDATE payment_policy SET gate_before_acceptance = p_gate, updated_at = clock_timestamp()
     WHERE gate_before_acceptance IS DISTINCT FROM p_gate;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$;

-- --- Fees --------------------------------------------------------------------------------------

CREATE TABLE fee_schedule (
    id                     uuid PRIMARY KEY DEFAULT uuidv7(),
    application_type_code  text NOT NULL CHECK (application_type_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    amount                 bigint NOT NULL CHECK (amount > 0 AND amount <= 10000000),
    currency               char(3) NOT NULL CHECK (currency = 'XAF'),
    label_fr               text NOT NULL CHECK (length(btrim(label_fr)) BETWEEN 3 AND 200),
    label_en               text NOT NULL CHECK (length(btrim(label_en)) BETWEEN 3 AND 200),
    demo                   boolean NOT NULL DEFAULT true CHECK (demo),
    valid_from             timestamptz NOT NULL,
    valid_to               timestamptz,
    CONSTRAINT ck_fee_period CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ix_fee_schedule_type ON fee_schedule (application_type_code, valid_from);

CREATE FUNCTION fee_schedule_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('fee_schedule:' || NEW.application_type_code));
    IF TG_OP = 'INSERT' THEN
        NEW.valid_from := clock_timestamp();
        NEW.valid_to := NULL;
        IF EXISTS (SELECT 1 FROM fee_schedule f WHERE f.application_type_code = NEW.application_type_code
                                                   AND (f.valid_to IS NULL OR f.valid_to > NEW.valid_from)) THEN
            RAISE EXCEPTION 'a fee for % is still in force: close it first', NEW.application_type_code
                USING ERRCODE = 'exclusion_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- A version is closed once, from now; nothing else of it ever changes.
    IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
       OR (NEW.id, NEW.application_type_code, NEW.amount, NEW.currency, NEW.label_fr, NEW.label_en, NEW.demo, NEW.valid_from)
          IS DISTINCT FROM
          (OLD.id, OLD.application_type_code, OLD.amount, OLD.currency, OLD.label_fr, OLD.label_en, OLD.demo, OLD.valid_from) THEN
        RAISE EXCEPTION 'a fee version is only ever closed, once' USING ERRCODE = 'restrict_violation';
    END IF;
    NEW.valid_to := greatest(clock_timestamp(), OLD.valid_from + interval '1 microsecond');
    RETURN NEW;
END;
$$;
CREATE TRIGGER fee_schedule_guard BEFORE INSERT OR UPDATE ON fee_schedule
    FOR EACH ROW EXECUTE FUNCTION fee_schedule_guard();
CREATE TRIGGER fee_schedule_no_delete BEFORE DELETE ON fee_schedule
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER fee_schedule_no_truncate BEFORE TRUNCATE ON fee_schedule
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- The fee in force for a type at a moment.
CREATE FUNCTION fee_in_force(p_type_code text, p_at timestamptz) RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT f.id FROM fee_schedule f
     WHERE f.application_type_code = p_type_code AND f.valid_from <= p_at
       AND (f.valid_to IS NULL OR f.valid_to > p_at)
     ORDER BY f.valid_from DESC LIMIT 1
$$;

-- --- Requests ----------------------------------------------------------------------------------

CREATE TABLE payment_request (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    application_id      uuid NOT NULL REFERENCES application (id),
    reference           text NOT NULL UNIQUE CHECK (reference ~ '^DEMO-PAY-[0-9]{4}-[0-9]{8}-[0-9A-Z]$'),
    fee_id              uuid NOT NULL REFERENCES fee_schedule (id),
    amount              bigint NOT NULL CHECK (amount > 0),
    currency            char(3) NOT NULL,
    status              text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'EXPIRED')),
    provider            text NOT NULL CHECK (provider = 'DEMO_SIMULATOR'),
    -- What the DEMO provider will do; exists only because the provider is a simulator.
    scenario            text NOT NULL CHECK (scenario IN ('SUCCEED', 'FAIL', 'NO_RESPONSE', 'DUPLICATE',
                                                          'WRONG_AMOUNT', 'UNKNOWN_REFERENCE')),
    provider_charge_id  text UNIQUE CHECK (provider_charge_id ~ '^[A-Za-z0-9._:-]{8,100}$'),
    requested_by        uuid NOT NULL REFERENCES app_user (id),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at          timestamptz NOT NULL,
    settled_at          timestamptz,
    settled_by_record   uuid,
    CONSTRAINT ck_payment_expiry CHECK (expires_at >= created_at + interval '1 minute'
                                        AND expires_at <= created_at + interval '7 days'),
    CONSTRAINT ck_payment_settled CHECK ((status = 'PENDING') = (settled_at IS NULL)
                                         AND (status = 'PAID') = (settled_by_record IS NOT NULL))
);
-- One payment at a time: after a failure or an expiry the applicant may pay again.
CREATE UNIQUE INDEX ux_payment_request_open ON payment_request (application_id) WHERE status IN ('PENDING', 'PAID');
CREATE INDEX ix_payment_request_application ON payment_request (application_id, created_at);
CREATE INDEX ix_payment_request_pending ON payment_request (expires_at) WHERE status = 'PENDING';

CREATE FUNCTION payment_request_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    a application;
    fee fee_schedule;
    ttl interval;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO a FROM application WHERE id = NEW.application_id;
        IF a.id IS NULL OR NEW.requested_by IS DISTINCT FROM current_actor() OR a.applicant_id <> NEW.requested_by THEN
            RAISE EXCEPTION 'only the applicant pays for an application' USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF a.state NOT IN ('SUBMITTED', 'COMPLETENESS_REVIEW', 'INFO_REQUESTED', 'UNDER_REVIEW', 'PENDING_DECISION') THEN
            RAISE EXCEPTION 'application % is not in progress', a.id USING ERRCODE = 'restrict_violation';
        END IF;
        SELECT * INTO fee FROM fee_schedule WHERE id = fee_in_force(a.type_code, a.submitted_at);
        IF fee.id IS NULL THEN
            RAISE EXCEPTION 'no fee applies to application %', a.id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.provider IS DISTINCT FROM (SELECT provider FROM payment_policy) THEN
            RAISE EXCEPTION 'provider % is not approved', NEW.provider USING ERRCODE = 'check_violation';
        END IF;
        -- What is owed is the database's finding, never the caller's; so are the times.
        ttl := NEW.expires_at - NEW.created_at;
        NEW.fee_id := fee.id;
        NEW.amount := fee.amount;
        NEW.currency := fee.currency;
        NEW.status := 'PENDING';
        NEW.provider_charge_id := NULL;
        NEW.settled_at := NULL;
        NEW.settled_by_record := NULL;
        NEW.created_at := clock_timestamp();
        NEW.expires_at := NEW.created_at + ttl;
        RETURN NEW;
    END IF;

    IF (NEW.id, NEW.application_id, NEW.reference, NEW.fee_id, NEW.amount, NEW.currency, NEW.provider, NEW.scenario,
        NEW.requested_by, NEW.created_at, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.id, OLD.application_id, OLD.reference, OLD.fee_id, OLD.amount, OLD.currency, OLD.provider, OLD.scenario,
        OLD.requested_by, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'what a payment request asks never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.provider_charge_id IS DISTINCT FROM OLD.provider_charge_id
       AND (OLD.provider_charge_id IS NOT NULL OR OLD.status <> 'PENDING') THEN
        RAISE EXCEPTION 'the provider charge is recorded once, while pending' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF OLD.status <> 'PENDING' THEN
            RAISE EXCEPTION 'payment request % is final', OLD.reference USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.status = 'PAID' AND clock_timestamp() >= OLD.expires_at THEN
            RAISE EXCEPTION 'payment request % expired: it is never paid', OLD.reference USING ERRCODE = 'restrict_violation';
        END IF;
        IF NEW.status = 'EXPIRED' AND clock_timestamp() < OLD.expires_at THEN
            RAISE EXCEPTION 'payment request % has not expired yet', OLD.reference USING ERRCODE = 'restrict_violation';
        END IF;
        NEW.settled_at := clock_timestamp();
    ELSIF NEW.settled_at IS DISTINCT FROM OLD.settled_at OR NEW.settled_by_record IS DISTINCT FROM OLD.settled_by_record THEN
        RAISE EXCEPTION 'settlement belongs to a status change' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_request_guard BEFORE INSERT OR UPDATE ON payment_request
    FOR EACH ROW EXECUTE FUNCTION payment_request_guard();
CREATE TRIGGER payment_request_no_delete BEFORE DELETE ON payment_request
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER payment_request_no_truncate BEFORE TRUNCATE ON payment_request
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Provider reports --------------------------------------------------------------------------

CREATE TABLE payment_record (
    id                       uuid PRIMARY KEY DEFAULT uuidv7(),
    provider                 text NOT NULL CHECK (provider = 'DEMO_SIMULATOR'),
    provider_transaction_id  text NOT NULL UNIQUE CHECK (provider_transaction_id ~ '^[A-Za-z0-9._:-]{8,100}$'),
    reference                text NOT NULL CHECK (length(reference) BETWEEN 1 AND 64),
    amount                   bigint NOT NULL,
    currency                 text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reported_status          text NOT NULL CHECK (reported_status IN ('SUCCEEDED', 'FAILED')),
    occurred_at              timestamptz,
    received_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    key_version              int NOT NULL CHECK (key_version >= 1),
    payment_request_id       uuid REFERENCES payment_request (id),
    outcome                  text NOT NULL CHECK (outcome IN ('MATCHED', 'FAILED', 'AMOUNT_MISMATCH', 'UNMATCHED_REFERENCE',
                                                              'AFTER_EXPIRY', 'ALREADY_SETTLED', 'NO_EFFECT')),
    exception                boolean GENERATED ALWAYS AS (outcome IN ('AMOUNT_MISMATCH', 'UNMATCHED_REFERENCE',
                                                                       'AFTER_EXPIRY', 'ALREADY_SETTLED')) STORED,
    CONSTRAINT ck_record_match CHECK ((outcome = 'UNMATCHED_REFERENCE') = (payment_request_id IS NULL)
                                      OR (outcome = 'NO_EFFECT'))
);
CREATE INDEX ix_payment_record_request ON payment_record (payment_request_id);
CREATE INDEX ix_payment_record_exception ON payment_record (received_at) WHERE exception;
ALTER TABLE payment_request ADD CONSTRAINT fk_payment_request_record
    FOREIGN KEY (settled_by_record) REFERENCES payment_record (id);

CREATE TRIGGER payment_record_append_only BEFORE UPDATE OR DELETE ON payment_record
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER payment_record_no_truncate BEFORE TRUNCATE ON payment_record
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Decides one authenticated provider report. Returns the outcome, whether it was a duplicate
-- (then nothing changed and the first outcome is returned), and whether the request expired now.
CREATE FUNCTION payment_callback_apply(p_provider text, p_transaction text, p_reference text, p_amount bigint,
                                       p_currency text, p_status text, p_occurred_at timestamptz, p_key_version int)
RETURNS TABLE (record_id uuid, outcome text, duplicate boolean, expired_now boolean, request_id uuid,
               request_reference text, application_id uuid, expected_amount bigint)
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    r payment_request;
    v_outcome text;
    v_expired boolean := false;
    v_record uuid;
BEGIN
    IF p_provider IS DISTINCT FROM (SELECT provider FROM payment_policy) THEN
        RAISE EXCEPTION 'provider % is not approved', p_provider USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Serialise every report on one request; reports naming nothing serialise on the unique index.
    SELECT * INTO r FROM payment_request pr WHERE pr.reference = p_reference FOR UPDATE;

    SELECT pr.id, pr.outcome INTO v_record, v_outcome FROM payment_record pr WHERE pr.provider_transaction_id = p_transaction;
    IF v_record IS NOT NULL THEN
        RETURN QUERY SELECT v_record, v_outcome, true, false, r.id, r.reference, r.application_id, r.amount;
        RETURN;
    END IF;

    IF r.id IS NOT NULL AND r.status = 'PENDING' AND clock_timestamp() >= r.expires_at THEN
        UPDATE payment_request SET status = 'EXPIRED' WHERE id = r.id;
        r.status := 'EXPIRED';
        v_expired := true;
    END IF;

    v_outcome := CASE
        WHEN p_status = 'FAILED' THEN
            CASE WHEN r.status = 'PENDING' AND r.amount = p_amount AND r.currency = p_currency THEN 'FAILED' ELSE 'NO_EFFECT' END
        WHEN r.id IS NULL THEN 'UNMATCHED_REFERENCE'
        WHEN r.status = 'EXPIRED' THEN 'AFTER_EXPIRY'
        WHEN r.status <> 'PENDING' THEN 'ALREADY_SETTLED'
        WHEN r.amount <> p_amount OR r.currency <> p_currency THEN 'AMOUNT_MISMATCH'
        ELSE 'MATCHED' END;

    INSERT INTO payment_record (provider, provider_transaction_id, reference, amount, currency, reported_status,
                                occurred_at, key_version, payment_request_id, outcome)
    VALUES (p_provider, p_transaction, p_reference, p_amount, p_currency, p_status, p_occurred_at, p_key_version,
            r.id, v_outcome)
    ON CONFLICT (provider_transaction_id) DO NOTHING
    RETURNING id INTO v_record;
    IF v_record IS NULL THEN
        -- A concurrent copy won the insert (no request to lock): this one is the duplicate.
        SELECT pr.id, pr.outcome INTO v_record, v_outcome FROM payment_record pr WHERE pr.provider_transaction_id = p_transaction;
        RETURN QUERY SELECT v_record, v_outcome, true, v_expired, r.id, r.reference, r.application_id, r.amount;
        RETURN;
    END IF;

    IF v_outcome = 'MATCHED' THEN
        UPDATE payment_request SET status = 'PAID', settled_by_record = v_record WHERE id = r.id;
    ELSIF v_outcome = 'FAILED' THEN
        UPDATE payment_request SET status = 'FAILED' WHERE id = r.id;
    END IF;
    RETURN QUERY SELECT v_record, v_outcome, false, v_expired, r.id, r.reference, r.application_id, r.amount;
END;
$$;

-- Marks every pending request past its expiry as expired; returns them for the audit.
CREATE FUNCTION payment_expire_due()
RETURNS TABLE (request_id uuid, reference text, application_id uuid)
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    UPDATE payment_request SET status = 'EXPIRED'
     WHERE status = 'PENDING' AND expires_at <= clock_timestamp()
    RETURNING id, reference, application_id
$$;

-- --- The gate ----------------------------------------------------------------------------------

-- Whether acceptance must wait for payment: the gate is on, a fee was in force at submission,
-- and no request is paid.
CREATE FUNCTION payment_blocks_acceptance(p_application uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT (SELECT gate_before_acceptance FROM payment_policy)
       AND EXISTS (SELECT 1 FROM application a WHERE a.id = p_application
                      AND a.submitted_at IS NOT NULL AND fee_in_force(a.type_code, a.submitted_at) IS NOT NULL)
       AND NOT EXISTS (SELECT 1 FROM payment_request pr WHERE pr.application_id = p_application AND pr.status = 'PAID')
$$;

CREATE FUNCTION completeness_payment_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    IF NEW.kind = 'COMPLETE' AND payment_blocks_acceptance(NEW.application_id) THEN
        RAISE EXCEPTION 'application % is not paid: completeness cannot be accepted', NEW.application_id
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER completeness_payment_guard BEFORE INSERT ON completeness_record
    FOR EACH ROW EXECUTE FUNCTION completeness_payment_guard();

-- --- Exceptions --------------------------------------------------------------------------------

CREATE TABLE payment_exception_resolution (
    id                 uuid PRIMARY KEY DEFAULT uuidv7(),
    payment_record_id  uuid NOT NULL UNIQUE REFERENCES payment_record (id),
    resolution         text NOT NULL CHECK (resolution IN ('REFUND_SIMULATED', 'NO_ACTION')),
    note               text NOT NULL CHECK (length(btrim(note)) BETWEEN 10 AND 1000),
    resolved_by        uuid NOT NULL REFERENCES app_user (id),
    at                 timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION payment_exception_resolution_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    rec payment_record;
BEGIN
    SELECT * INTO rec FROM payment_record WHERE id = NEW.payment_record_id;
    IF rec.id IS NULL OR NOT rec.exception THEN
        RAISE EXCEPTION 'only an exception is resolved' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.resolved_by IS DISTINCT FROM current_actor()
       OR NOT holds_permission_at(NEW.resolved_by, 'payment.reconcile', NULL) THEN
        RAISE EXCEPTION 'resolving needs payment.reconcile' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF EXISTS (SELECT 1 FROM payment_request pr JOIN application a ON a.id = pr.application_id
                WHERE pr.id = rec.payment_request_id AND a.applicant_id = NEW.resolved_by) THEN
        RAISE EXCEPTION 'nobody resolves a payment of their own application' USING ERRCODE = 'insufficient_privilege';
    END IF;
    NEW.at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_exception_resolution_guard BEFORE INSERT ON payment_exception_resolution
    FOR EACH ROW EXECUTE FUNCTION payment_exception_resolution_guard();
CREATE TRIGGER payment_exception_resolution_append_only BEFORE UPDATE OR DELETE ON payment_exception_resolution
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER payment_exception_resolution_no_truncate BEFORE TRUNCATE ON payment_exception_resolution
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- Inserts a resolution as the owner of the tables (the finance officer reads no payment_record
-- directly); the guard checks the actor, the permission and the record. Returns its id.
CREATE FUNCTION payment_exception_resolve(p_record uuid, p_resolution text, p_note text) RETURNS uuid
LANGUAGE sql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    INSERT INTO payment_exception_resolution (payment_record_id, resolution, note, resolved_by)
    VALUES (p_record, p_resolution, p_note, current_actor())
    RETURNING id
$$;

-- --- Finance views (payment.reconcile, read as the owner) -------------------------------------

CREATE FUNCTION payment_reconciler() RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT current_actor() IS NOT NULL AND holds_permission_at(current_actor(), 'payment.reconcile', NULL)
$$;

CREATE FUNCTION payment_requests_for_finance(p_status text, p_limit int, p_offset int)
RETURNS TABLE (id uuid, reference text, application_reference text, amount bigint, currency text, status text,
               created_at timestamptz, expires_at timestamptz, settled_at timestamptz, transaction_id text)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT pr.id, pr.reference, a.reference, pr.amount, pr.currency::text,
           CASE WHEN pr.status = 'PENDING' AND pr.expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE pr.status END,
           pr.created_at, pr.expires_at, pr.settled_at, rec.provider_transaction_id
      FROM payment_request pr
      JOIN application a ON a.id = pr.application_id
      LEFT JOIN payment_record rec ON rec.id = pr.settled_by_record
     WHERE payment_reconciler()
       AND (p_status IS NULL OR pr.status = p_status)
     ORDER BY pr.created_at DESC, pr.id
     LIMIT greatest(p_limit, 0) OFFSET greatest(p_offset, 0)
$$;

CREATE FUNCTION payment_exceptions_for_finance(p_resolved boolean, p_limit int, p_offset int)
RETURNS TABLE (id uuid, transaction_id text, reported_reference text, reported_amount bigint, reported_currency text,
               outcome text, received_at timestamptz, request_reference text, expected_amount bigint,
               request_status text, application_reference text, resolution text, resolution_note text,
               resolved_by_name text, resolved_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT rec.id, rec.provider_transaction_id, rec.reference, rec.amount, rec.currency, rec.outcome, rec.received_at,
           pr.reference, pr.amount, pr.status, a.reference, res.resolution, res.note, u.display_name, res.at
      FROM payment_record rec
      LEFT JOIN payment_request pr ON pr.id = rec.payment_request_id
      LEFT JOIN application a ON a.id = pr.application_id
      LEFT JOIN payment_exception_resolution res ON res.payment_record_id = rec.id
      LEFT JOIN app_user u ON u.id = res.resolved_by
     WHERE payment_reconciler() AND rec.exception
       AND (p_resolved IS NULL OR (res.id IS NOT NULL) = p_resolved)
     ORDER BY rec.received_at DESC, rec.id
     LIMIT greatest(p_limit, 0) OFFSET greatest(p_offset, 0)
$$;

CREATE FUNCTION payment_finance_summary()
RETURNS TABLE (pending bigint, paid bigint, failed bigint, expired bigint, open_exceptions bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT count(*) FILTER (WHERE pr.status = 'PENDING' AND pr.expires_at > clock_timestamp()),
           count(*) FILTER (WHERE pr.status = 'PAID'),
           count(*) FILTER (WHERE pr.status = 'FAILED'),
           count(*) FILTER (WHERE pr.status = 'EXPIRED' OR (pr.status = 'PENDING' AND pr.expires_at <= clock_timestamp())),
           (SELECT count(*) FROM payment_record rec
             WHERE rec.exception AND NOT EXISTS (SELECT 1 FROM payment_exception_resolution res
                                                  WHERE res.payment_record_id = rec.id))
      FROM payment_request pr
     WHERE payment_reconciler()
    HAVING payment_reconciler()
$$;

-- --- Row-level security ------------------------------------------------------------------------

ALTER TABLE payment_request ENABLE ROW LEVEL SECURITY;
-- Whoever sees the application (its applicant, its office's staff) sees what it owes.
CREATE POLICY payment_request_read ON payment_request FOR SELECT TO snigf_app
    USING (EXISTS (SELECT 1 FROM application a WHERE a.id = application_id));
CREATE POLICY payment_request_open ON payment_request FOR INSERT TO snigf_app
    WITH CHECK (requested_by = current_actor());
-- The applicant records the provider's charge id; the guard allows nothing else.
CREATE POLICY payment_request_charge ON payment_request FOR UPDATE TO snigf_app
    USING (requested_by = current_actor()) WITH CHECK (requested_by = current_actor());

-- --- Grants ------------------------------------------------------------------------------------

REVOKE ALL ON payment_policy, fee_schedule, payment_request, payment_record, payment_exception_resolution FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION payment_policy_apply(boolean), payment_callback_apply(text, text, text, bigint, text, text, timestamptz, int),
    payment_expire_due(), payment_blocks_acceptance(uuid), payment_exception_resolve(uuid, text, text),
    payment_requests_for_finance(text, int, int), payment_exceptions_for_finance(boolean, int, int),
    payment_finance_summary() FROM PUBLIC;
GRANT SELECT ON payment_policy TO snigf_app;
GRANT SELECT, INSERT ON fee_schedule TO snigf_app;
GRANT UPDATE (valid_to) ON fee_schedule TO snigf_app;
GRANT SELECT, INSERT ON payment_request TO snigf_app;
GRANT UPDATE (provider_charge_id) ON payment_request TO snigf_app;
GRANT EXECUTE ON FUNCTION payment_policy_apply(boolean), payment_callback_apply(text, text, text, bigint, text, text, timestamptz, int),
    payment_expire_due(), payment_blocks_acceptance(uuid), payment_exception_resolve(uuid, text, text),
    payment_requests_for_finance(text, int, int), payment_exceptions_for_finance(boolean, int, int),
    payment_finance_summary() TO snigf_app;
