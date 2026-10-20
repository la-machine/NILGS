-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V2 — parcels: stable identity, append-only versions, reference numbering, and
-- the geometry normalization pipeline (Slice 1 design §4, baseline Task D).
--
-- Storage CRS is EPSG:4326 (ADR-006). Accepted input CRS are provisional (P1).
-- Validation happens here, in PostGIS, not in application code (MVP-04 rule).

-- ---------------------------------------------------------------------------
-- Reference numbering: one counter row per (prefix, office, year).
-- INSERT ... ON CONFLICT takes a row lock, so concurrent callers are serialised
-- and never receive the same number. Gaps are possible (rolled-back
-- transactions) and acceptable; uniqueness is guaranteed by constraints.
-- ---------------------------------------------------------------------------
CREATE TABLE reference_counter (
    prefix       text NOT NULL CHECK (prefix ~ '^[A-Z]{3}$'),
    office_code  varchar(16) NOT NULL,
    year         int NOT NULL CHECK (year BETWEEN 2000 AND 2999),
    next_value   bigint NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    PRIMARY KEY (prefix, office_code, year)
);

CREATE FUNCTION next_reference_number(p_prefix text, p_office_code text, p_year int)
RETURNS bigint
LANGUAGE sql VOLATILE AS $$
    INSERT INTO reference_counter AS rc (prefix, office_code, year, next_value)
    VALUES (p_prefix, p_office_code, p_year, 2)
    ON CONFLICT (prefix, office_code, year) DO UPDATE SET next_value = rc.next_value + 1
    RETURNING next_value - 1;
$$;

-- ---------------------------------------------------------------------------
-- Parcel identity and versions
-- ---------------------------------------------------------------------------
CREATE TABLE parcel (
    id                  uuid PRIMARY KEY,
    reference           varchar(40) NOT NULL UNIQUE
                        CHECK (reference ~ '^DEMO-PCL-[A-Z0-9]{2,16}-[0-9]{4}-[0-9]{6}-[0-9A-Z]$'),
    office_id           uuid NOT NULL REFERENCES office (id),
    status              text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'RETIRED')),
    current_version_no  int NOT NULL CHECK (current_version_no >= 1),
    created_by          uuid NOT NULL REFERENCES app_user (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0   -- optimistic lock (JPA @Version)
);
CREATE INDEX ix_parcel_office ON parcel (office_id);

CREATE TABLE parcel_version (
    id                     uuid PRIMARY KEY,
    parcel_id              uuid NOT NULL REFERENCES parcel (id),
    version_no             int  NOT NULL CHECK (version_no >= 1),
    geom                   geometry(MultiPolygon, 4326) NOT NULL,
    area_m2                numeric(14, 2) NOT NULL CHECK (area_m2 > 0),
    source_srid            int NOT NULL CHECK (source_srid IN (4326, 32632, 32633)),
    source_geojson_sha256  char(64) NOT NULL CHECK (source_geojson_sha256 ~ '^[0-9a-f]{64}$'),
    locality               text NOT NULL CHECK (length(locality) BETWEEN 2 AND 200),
    external_reference     text CHECK (external_reference IS NULL OR length(external_reference) BETWEEN 1 AND 100),
    verification_status    text NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED')),
    change_reason          text,
    created_by             uuid NOT NULL REFERENCES app_user (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parcel_id, version_no),
    -- C8: every version after the first carries a reason of at least 10 characters.
    CONSTRAINT ck_reason_on_change CHECK (version_no = 1 OR coalesce(length(change_reason), 0) >= 10),
    CONSTRAINT ck_geom_valid       CHECK (ST_IsValid(geom) AND NOT ST_IsEmpty(geom)),
    CONSTRAINT ck_geom_vertices    CHECK (ST_NPoints(geom) <= 10000)   -- P10
);
CREATE INDEX ix_parcel_version_geom ON parcel_version USING gist (geom);

-- The parcel always points at an existing version (checked at commit time, so the
-- parcel row and its first version can be inserted in the same transaction).
ALTER TABLE parcel ADD CONSTRAINT fk_parcel_current_version
    FOREIGN KEY (id, current_version_no) REFERENCES parcel_version (parcel_id, version_no)
    DEFERRABLE INITIALLY DEFERRED;

-- Versions are numbered 1, 2, 3 ... without gaps.
CREATE FUNCTION parcel_version_sequence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    expected int;
BEGIN
    SELECT coalesce(max(version_no), 0) + 1 INTO expected
      FROM parcel_version WHERE parcel_id = NEW.parcel_id;
    IF NEW.version_no <> expected THEN
        RAISE EXCEPTION 'parcel % expects version %, got %', NEW.parcel_id, expected, NEW.version_no
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER parcel_version_sequence BEFORE INSERT ON parcel_version
    FOR EACH ROW EXECUTE FUNCTION parcel_version_sequence();

-- Versions are immutable history.
CREATE TRIGGER parcel_version_no_change BEFORE UPDATE OR DELETE ON parcel_version
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER parcel_version_no_truncate BEFORE TRUNCATE ON parcel_version
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- The current version pointer only moves forward; parcels are never deleted.
CREATE FUNCTION parcel_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.current_version_no < OLD.current_version_no THEN
        RAISE EXCEPTION 'parcel % cannot move back from version % to %',
            OLD.id, OLD.current_version_no, NEW.current_version_no USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER parcel_guard BEFORE UPDATE ON parcel FOR EACH ROW EXECUTE FUNCTION parcel_guard();
CREATE TRIGGER parcel_no_delete BEFORE DELETE ON parcel FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER parcel_no_truncate BEFORE TRUNCATE ON parcel FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

-- ---------------------------------------------------------------------------
-- Geometry normalization (Slice 1 design §4 "Validation order").
-- Returns ok = false with a stable error code instead of raising, so the API
-- can map each code to a localized message (ADR-011).
-- ---------------------------------------------------------------------------
CREATE TYPE geometry_check_result AS (
    ok            boolean,
    error_code    text,
    error_detail  text,
    geom          geometry,
    area_m2       numeric(14, 2),
    source_srid   int
);

CREATE FUNCTION normalize_parcel_geometry(
    p_geojson       text,
    p_srid          int,
    p_max_vertices  int      DEFAULT 10000,                                      -- P10
    p_bounds        geometry DEFAULT ST_MakeEnvelope(8.0, 1.5, 16.5, 13.5, 4326) -- DEMO bounding box around Cameroon
) RETURNS geometry_check_result
LANGUAGE plpgsql STABLE AS $$
DECLARE
    j  jsonb;
    g  geometry;
    r  geometry_check_result;
BEGIN
    r.ok := false;
    r.source_srid := p_srid;

    IF p_srid IS NULL THEN
        r.error_code := 'CRS_MISSING';
        RETURN r;
    END IF;
    IF p_srid NOT IN (4326, 32632, 32633) THEN
        r.error_code := 'CRS_UNSUPPORTED';
        r.error_detail := format('EPSG:%s is not accepted; use 4326, 32632 or 32633', p_srid);
        RETURN r;
    END IF;

    BEGIN
        j := p_geojson::jsonb;
    EXCEPTION WHEN others THEN
        r.error_code := 'GEOMETRY_MALFORMED';
        r.error_detail := 'not valid JSON';
        RETURN r;
    END;
    IF j IS NULL OR jsonb_typeof(j) <> 'object' THEN
        r.error_code := 'GEOMETRY_MALFORMED';
        r.error_detail := 'a GeoJSON geometry object is required';
        RETURN r;
    END IF;
    IF j ? 'crs' THEN
        -- RFC 7946 removed the crs member; the CRS travels in the srid field only.
        r.error_code := 'GEOMETRY_MALFORMED';
        r.error_detail := 'the GeoJSON crs member is not accepted; send srid separately';
        RETURN r;
    END IF;
    IF coalesce(j ->> 'type', '') NOT IN ('Polygon', 'MultiPolygon') THEN
        r.error_code := 'GEOMETRY_TYPE_UNSUPPORTED';
        r.error_detail := format('type %s is not accepted; use Polygon or MultiPolygon', coalesce(j ->> 'type', 'missing'));
        RETURN r;
    END IF;

    BEGIN
        g := ST_GeomFromGeoJSON(j::text);
    EXCEPTION WHEN others THEN
        r.error_code := 'GEOMETRY_MALFORMED';
        r.error_detail := left(SQLERRM, 200);
        RETURN r;
    END;

    IF g IS NULL OR ST_IsEmpty(g) THEN
        r.error_code := 'GEOMETRY_EMPTY';
        RETURN r;
    END IF;
    g := ST_Force2D(g);   -- heights are irrelevant to a parcel footprint
    IF ST_NPoints(g) > p_max_vertices THEN
        r.error_code := 'GEOMETRY_TOO_COMPLEX';
        r.error_detail := format('%s vertices; the limit is %s', ST_NPoints(g), p_max_vertices);
        RETURN r;
    END IF;

    g := ST_SetSRID(g, p_srid);
    IF NOT ST_IsValid(g) THEN
        r.error_code := 'GEOMETRY_INVALID';
        r.error_detail := ST_IsValidReason(g);
        RETURN r;
    END IF;

    IF p_srid <> 4326 THEN
        BEGIN
            g := ST_Transform(g, 4326);
        EXCEPTION WHEN others THEN
            r.error_code := 'GEOMETRY_INVALID_AFTER_TRANSFORM';
            r.error_detail := left(SQLERRM, 200);
            RETURN r;
        END;
    END IF;

    -- P3: snap to 1e-7 degrees (about 1 cm) so stored values and tests are deterministic.
    g := ST_ReducePrecision(g, 1e-7);
    g := ST_CollectionExtract(g, 3);
    IF g IS NULL OR ST_IsEmpty(g) THEN
        r.error_code := 'GEOMETRY_EMPTY';
        r.error_detail := 'the geometry collapses at 1 cm precision';
        RETURN r;
    END IF;
    IF NOT ST_IsValid(g) THEN
        r.error_code := 'GEOMETRY_INVALID_AFTER_TRANSFORM';
        r.error_detail := ST_IsValidReason(g);
        RETURN r;
    END IF;
    g := ST_Multi(g);

    IF NOT ST_Within(g, p_bounds) THEN
        -- Also catches swapped latitude/longitude and wrong-zone UTM input.
        r.error_code := 'GEOMETRY_OUT_OF_BOUNDS';
        r.error_detail := 'the parcel lies outside the accepted area';
        RETURN r;
    END IF;

    r.area_m2 := round(ST_Area(g::geography, true)::numeric, 2);
    IF r.area_m2 <= 0 THEN
        r.error_code := 'GEOMETRY_EMPTY';
        r.error_detail := 'area rounds to zero';
        RETURN r;
    END IF;

    r.ok := true;
    r.geom := g;
    RETURN r;
END;
$$;

-- Grants
REVOKE EXECUTE ON FUNCTION next_reference_number(text, text, int) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION normalize_parcel_geometry(text, int, int, geometry) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION next_reference_number(text, text, int) TO snigf_app;
GRANT EXECUTE ON FUNCTION normalize_parcel_geometry(text, int, int, geometry) TO snigf_app;
GRANT SELECT, INSERT, UPDATE ON reference_counter TO snigf_app;
GRANT SELECT, INSERT ON parcel TO snigf_app;
GRANT UPDATE (status, current_version_no, version) ON parcel TO snigf_app;
GRANT SELECT, INSERT ON parcel_version TO snigf_app;
