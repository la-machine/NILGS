-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V11: overlap and duplicate detection (MVP-04, task E-10, ADR-030).
--
-- Every parcel version written is compared, in the same transaction, with the current version
-- of every other active parcel. The database does it (a trigger on parcel_version), so no
-- code path can write a version without the check:
--   OVERLAP              interiors intersect (DE-9IM T********) over at least the policy's
--                        minimum area, on the spheroid; a shared edge or corner is not one
--   DUPLICATE_GEOMETRY   an overlap covering at least the policy's share of each parcel
--   DUPLICATE_REFERENCE  the same normalised external reference on two active parcels
-- One open alert per parcel pair and type. An alert keeps its evidence: both version ids,
-- the intersection area, both shares and the intersection geometry. A new version of a parcel
-- closes its open alerts as SUPERSEDED (their evidence names the old version) and is checked
-- afresh. Alerts are created only here: the application cannot insert, change evidence or
-- delete them. Resolution (E-11) is an update the guard below checks: a listed outcome, a
-- note, a resolver who holds alert.resolve at an office of either parcel and authored neither
-- compared version.

SET search_path = snigf, public;

-- --- Policy: the thresholds, set from the application's settings (SNIGF_CONFLICT_*) ---------

CREATE TABLE conflict_policy (
    id               boolean PRIMARY KEY DEFAULT true CHECK (id),
    min_overlap_m2   numeric(10, 2) NOT NULL CHECK (min_overlap_m2 >= 0.01 AND min_overlap_m2 <= 1000),
    duplicate_share  numeric(4, 3) NOT NULL CHECK (duplicate_share >= 0.5 AND duplicate_share <= 1),
    updated_at       timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- The Baseline's demonstration thresholds (MVP-04), until the application applies its settings.
INSERT INTO conflict_policy (min_overlap_m2, duplicate_share) VALUES (1.00, 0.950);

-- --- Alerts ------------------------------------------------------------------------------

CREATE TABLE conflict_alert (
    id                        uuid PRIMARY KEY DEFAULT uuidv7(),
    alert_type                text NOT NULL CHECK (alert_type IN ('OVERLAP', 'DUPLICATE_GEOMETRY', 'DUPLICATE_REFERENCE')),
    parcel_a_id               uuid NOT NULL REFERENCES parcel (id),
    parcel_b_id               uuid NOT NULL REFERENCES parcel (id),
    version_a_id              uuid NOT NULL REFERENCES parcel_version (id),
    version_b_id              uuid NOT NULL REFERENCES parcel_version (id),
    intersection_m2           numeric(14, 2),
    share_a                   numeric(6, 5),
    share_b                   numeric(6, 5),
    intersection_geom         geometry(MultiPolygon, 4326),
    normalized_reference      text,
    raised_by_version_id      uuid NOT NULL REFERENCES parcel_version (id),
    raised_at                 timestamptz NOT NULL DEFAULT clock_timestamp(),
    status                    text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'SUPERSEDED')),
    resolution                text CHECK (resolution IN ('NO_CONFLICT', 'DATA_ERROR_CORRECTED', 'REFERRED_FOR_ADJUDICATION')),
    resolution_note           text,
    resolved_by               uuid REFERENCES app_user (id),
    resolved_at               timestamptz,
    superseded_by_version_id  uuid REFERENCES parcel_version (id),
    superseded_at             timestamptz,
    version                   bigint NOT NULL DEFAULT 0,   -- optimistic lock
    CONSTRAINT ck_pair_ordered CHECK (parcel_a_id < parcel_b_id),
    CONSTRAINT ck_geometry_evidence CHECK (
        (alert_type = 'DUPLICATE_REFERENCE' AND normalized_reference IS NOT NULL)
        OR (alert_type <> 'DUPLICATE_REFERENCE' AND intersection_m2 > 0 AND intersection_geom IS NOT NULL
            AND share_a > 0 AND share_a <= 1.00001 AND share_b > 0 AND share_b <= 1.00001)),
    CONSTRAINT ck_resolved CHECK (
        (status = 'RESOLVED') = (resolution IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
                                 AND coalesce(length(btrim(resolution_note)), 0) >= 10)),
    CONSTRAINT ck_superseded CHECK (
        (status = 'SUPERSEDED') = (superseded_by_version_id IS NOT NULL AND superseded_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_conflict_alert_open_pair ON conflict_alert (parcel_a_id, parcel_b_id, alert_type)
    WHERE status = 'OPEN';
CREATE INDEX ix_conflict_alert_parcel_a ON conflict_alert (parcel_a_id, status);
CREATE INDEX ix_conflict_alert_parcel_b ON conflict_alert (parcel_b_id, status);
CREATE INDEX ix_conflict_alert_raised_by ON conflict_alert (raised_by_version_id);

-- --- Detection -------------------------------------------------------------------------

-- Spaces, punctuation and case do not make two external references different.
CREATE FUNCTION normalize_external_reference(p_reference text) RETURNS text
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT nullif(upper(regexp_replace(coalesce(p_reference, ''), '[^[:alnum:]]', '', 'g')), '')
$$;

-- Compares one version with the current versions of the other active parcels and raises the
-- alerts its pairs deserve (at most one open per pair and type). Returns how many it raised.
CREATE FUNCTION raise_conflicts_for_version(p_version_id uuid) RETURNS int
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    pol      conflict_policy;
    v        parcel_version;
    other    record;
    inter    geometry;
    m2       numeric;
    share_v  numeric;
    share_o  numeric;
    kind     text;
    norm     text;
    raised   int := 0;
    added    int;
BEGIN
    SELECT * INTO pol FROM conflict_policy;
    SELECT * INTO v FROM parcel_version WHERE id = p_version_id;
    IF v.id IS NULL THEN
        RETURN 0;
    END IF;

    FOR other IN
        SELECT pv.id, pv.parcel_id, pv.geom, pv.area_m2
          FROM parcel p
          JOIN parcel_version pv ON pv.parcel_id = p.id AND pv.version_no = p.current_version_no
         WHERE p.status = 'ACTIVE' AND p.id <> v.parcel_id
           AND pv.geom && v.geom                              -- GiST index first
           AND ST_Relate(pv.geom, v.geom, 'T********')        -- interiors intersect
    LOOP
        inter := ST_Multi(ST_CollectionExtract(ST_Intersection(v.geom, other.geom), 3));
        IF inter IS NULL OR ST_IsEmpty(inter) THEN
            CONTINUE;
        END IF;
        m2 := ST_Area(inter::geography);                      -- on the spheroid, m2
        IF m2 < pol.min_overlap_m2 THEN
            CONTINUE;
        END IF;
        share_v := least(m2 / v.area_m2, 1);
        share_o := least(m2 / other.area_m2, 1);
        kind := CASE WHEN share_v >= pol.duplicate_share AND share_o >= pol.duplicate_share
                     THEN 'DUPLICATE_GEOMETRY' ELSE 'OVERLAP' END;
        INSERT INTO conflict_alert (alert_type, parcel_a_id, parcel_b_id, version_a_id, version_b_id,
                                    intersection_m2, share_a, share_b, intersection_geom, raised_by_version_id)
        SELECT kind,
               least(v.parcel_id, other.parcel_id), greatest(v.parcel_id, other.parcel_id),
               CASE WHEN v.parcel_id < other.parcel_id THEN v.id ELSE other.id END,
               CASE WHEN v.parcel_id < other.parcel_id THEN other.id ELSE v.id END,
               round(m2, 2),
               round(CASE WHEN v.parcel_id < other.parcel_id THEN share_v ELSE share_o END, 5),
               round(CASE WHEN v.parcel_id < other.parcel_id THEN share_o ELSE share_v END, 5),
               inter, v.id
        ON CONFLICT (parcel_a_id, parcel_b_id, alert_type) WHERE status = 'OPEN' DO NOTHING;
        GET DIAGNOSTICS added = ROW_COUNT;
        raised := raised + added;
    END LOOP;

    norm := normalize_external_reference(v.external_reference);
    IF norm IS NOT NULL THEN
        FOR other IN
            SELECT pv.id, pv.parcel_id
              FROM parcel p
              JOIN parcel_version pv ON pv.parcel_id = p.id AND pv.version_no = p.current_version_no
             WHERE p.status = 'ACTIVE' AND p.id <> v.parcel_id
               AND normalize_external_reference(pv.external_reference) = norm
        LOOP
            INSERT INTO conflict_alert (alert_type, parcel_a_id, parcel_b_id, version_a_id, version_b_id,
                                        normalized_reference, raised_by_version_id)
            SELECT 'DUPLICATE_REFERENCE',
                   least(v.parcel_id, other.parcel_id), greatest(v.parcel_id, other.parcel_id),
                   CASE WHEN v.parcel_id < other.parcel_id THEN v.id ELSE other.id END,
                   CASE WHEN v.parcel_id < other.parcel_id THEN other.id ELSE v.id END,
                   norm, v.id
            ON CONFLICT (parcel_a_id, parcel_b_id, alert_type) WHERE status = 'OPEN' DO NOTHING;
            GET DIAGNOSTICS added = ROW_COUNT;
            raised := raised + added;
        END LOOP;
    END IF;
    RETURN raised;
END;
$$;

-- On every version written: close the parcel's open alerts (they name its previous version),
-- then check the new version. Writers are serialised for the check, so two parcels written at
-- the same moment still see each other (READ COMMITTED: the second check runs after the first
-- commits, with a fresh snapshot).
CREATE FUNCTION parcel_version_detect_conflicts() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('snigf.conflict-detection'));
    UPDATE conflict_alert
       SET status = 'SUPERSEDED', superseded_by_version_id = NEW.id, superseded_at = clock_timestamp(),
           version = version + 1
     WHERE status = 'OPEN' AND (parcel_a_id = NEW.parcel_id OR parcel_b_id = NEW.parcel_id);
    PERFORM raise_conflicts_for_version(NEW.id);
    RETURN NULL;
END;
$$;
CREATE TRIGGER parcel_version_detect_conflicts AFTER INSERT ON parcel_version
    FOR EACH ROW EXECUTE FUNCTION parcel_version_detect_conflicts();

-- --- Guard: evidence never changes; only OPEN alerts move, and resolution follows the rules ---

CREATE FUNCTION conflict_alert_guard() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
DECLARE
    office_a uuid;
    office_b uuid;
BEGIN
    -- Geometry '=' is exact (binary) equality in PostGIS 3, as evidence requires.
    IF (NEW.id, NEW.alert_type, NEW.parcel_a_id, NEW.parcel_b_id, NEW.version_a_id, NEW.version_b_id,
        NEW.intersection_m2, NEW.share_a, NEW.share_b, NEW.intersection_geom, NEW.normalized_reference,
        NEW.raised_by_version_id, NEW.raised_at)
       IS DISTINCT FROM
       (OLD.id, OLD.alert_type, OLD.parcel_a_id, OLD.parcel_b_id, OLD.version_a_id, OLD.version_b_id,
        OLD.intersection_m2, OLD.share_a, OLD.share_b, OLD.intersection_geom, OLD.normalized_reference,
        OLD.raised_by_version_id, OLD.raised_at) THEN
        RAISE EXCEPTION 'the evidence of alert % never changes', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'alert % is already %', OLD.id, OLD.status USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'alert % changed meanwhile', OLD.id USING ERRCODE = 'serialization_failure';
    END IF;
    IF NEW.status = 'RESOLVED' THEN
        IF NEW.resolved_by IN (SELECT created_by FROM parcel_version WHERE id IN (OLD.version_a_id, OLD.version_b_id)) THEN
            RAISE EXCEPTION 'the author of a compared version cannot resolve alert %', OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        SELECT office_id INTO office_a FROM parcel WHERE id = OLD.parcel_a_id;
        SELECT office_id INTO office_b FROM parcel WHERE id = OLD.parcel_b_id;
        IF NOT (holds_permission_at(NEW.resolved_by, 'alert.resolve', office_a)
                OR holds_permission_at(NEW.resolved_by, 'alert.resolve', office_b)) THEN
            RAISE EXCEPTION 'resolving alert % requires alert.resolve at an office of either parcel', OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        NEW.resolved_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER conflict_alert_guard BEFORE UPDATE ON conflict_alert
    FOR EACH ROW EXECUTE FUNCTION conflict_alert_guard();
CREATE TRIGGER conflict_alert_no_delete BEFORE DELETE ON conflict_alert
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER conflict_alert_no_truncate BEFORE TRUNCATE ON conflict_alert
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- --- Parcels recorded before this migration are checked once, oldest first ---------------

DO $$
DECLARE
    pv record;
BEGIN
    FOR pv IN
        SELECT v.id FROM parcel p
          JOIN parcel_version v ON v.parcel_id = p.id AND v.version_no = p.current_version_no
         WHERE p.status = 'ACTIVE'
         ORDER BY v.created_at, v.id
    LOOP
        PERFORM raise_conflicts_for_version(pv.id);
    END LOOP;
END;
$$;

-- --- Privileges -------------------------------------------------------------------------

REVOKE ALL ON conflict_policy, conflict_alert FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION raise_conflicts_for_version(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION parcel_version_detect_conflicts() FROM PUBLIC;
GRANT SELECT ON conflict_policy TO snigf_app;
GRANT UPDATE (min_overlap_m2, duplicate_share, updated_at) ON conflict_policy TO snigf_app;
GRANT SELECT ON conflict_alert TO snigf_app;
-- Resolution only (E-11); the guard checks the rest. No INSERT and no DELETE: alerts are raised
-- by the detection trigger alone and kept forever.
GRANT UPDATE (status, resolution, resolution_note, resolved_by, resolved_at, version) ON conflict_alert TO snigf_app;
