-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V12: resolving overlap and duplicate alerts (MVP-04, E-11, ADR-030).
--
-- alert.resolve is delegable (V7): a special grant in force may carry it, and the application
-- accepts it (ADR-026). V11's guard only knew role assignments, so it would refuse such a
-- resolver after the application had allowed them. The guard now accepts either, with the same
-- rule as the application: approved, not revoked, not expired, not used up, at the parcel's
-- office or everywhere. The author rule is unchanged and comes first: no permission, by role or
-- by grant, lets the author of a compared version resolve.
--
-- The resolution note gets an upper bound (2000 characters), as the application checks.

SET search_path = snigf, public;

-- Whether an enabled staff account holds a special grant in force for a permission at an
-- office (or everywhere). The use itself is counted by the application before the action.
CREATE FUNCTION holds_grant_at(p_user uuid, p_permission text, p_office uuid) RETURNS boolean
LANGUAGE sql STABLE
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1
          FROM app_user u
          JOIN permission_grant g ON g.user_id = u.id
         WHERE u.id = p_user AND u.enabled AND u.account_type = 'STAFF'
           AND g.permission = p_permission AND g.status = 'APPROVED'
           AND (g.valid_until IS NULL OR g.valid_until > clock_timestamp())
           AND (g.max_uses IS NULL OR g.uses <= g.max_uses)
           AND (g.office_id IS NULL OR g.office_id = p_office))
$$;

CREATE OR REPLACE FUNCTION conflict_alert_guard() RETURNS trigger
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
                OR holds_permission_at(NEW.resolved_by, 'alert.resolve', office_b)
                OR holds_grant_at(NEW.resolved_by, 'alert.resolve', office_a)
                OR holds_grant_at(NEW.resolved_by, 'alert.resolve', office_b)) THEN
            RAISE EXCEPTION 'resolving alert % requires alert.resolve at an office of either parcel', OLD.id
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        NEW.resolved_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE conflict_alert
    ADD CONSTRAINT ck_resolution_note_length CHECK (resolution_note IS NULL OR length(resolution_note) <= 2000);
