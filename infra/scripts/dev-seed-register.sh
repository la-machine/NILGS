#!/usr/bin/env bash
# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
# Synthetic land-register data for development (ADR-040, D5; docs/guides/land-register.md):
# DEMO parties, a notary, one synthetic parcel at YDE1 and DEMO title proposals.
#
#   bash infra/scripts/dev-seed-demo.sh        (first: the offices and the two conservators)
#   bash infra/scripts/dev-seed-register.sh    (the stack must be running)
#
# Nothing here is registered. Each proposal waits for a conservator to sign it with their security
# key in the browser: this script holds no key, and the register accepts nothing unsigned, so a
# seeded title can never pass for a signed one. Running it again adds nothing. Refused unless
# SNIGF_PROFILE=dev; never a migration, so this data can never exist outside development.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
[[ "$(sed -n 's/^SNIGF_PROFILE=//p' .env | tr -d '\r')" == "dev" ]] || { echo "refusing: SNIGF_PROFILE is not dev" >&2; exit 1; }

# Git Bash must not rewrite arguments for docker.
MSYS2_ARG_CONV_EXCL="*" docker compose exec -T -u postgres db psql -X -q -v ON_ERROR_STOP=1 -d snigf <<'SQL'
SET search_path = snigf, public;
DO $seed$
DECLARE
    office_id uuid := (SELECT id FROM office WHERE code = 'YDE1');
    actor     uuid := (SELECT id FROM app_user WHERE lower(email) = 'conservator.yde@demo.invalid');
    parcel_id uuid := '00000000-0000-7000-8000-00000000e001';
    version_id uuid := '00000000-0000-7000-8000-00000000e002';
    geometry  geometry_check_result;
    holder    uuid;
    who       record;
BEGIN
    IF office_id IS NULL OR actor IS NULL THEN
        RAISE EXCEPTION 'run infra/scripts/dev-seed-demo.sh first: YDE1 and conservator.yde@demo.invalid are needed';
    END IF;
    IF EXISTS (SELECT 1 FROM register_party p WHERE p.full_name = 'DEMO Seed Owner One') THEN
        RAISE NOTICE 'the register seed is already there';
        RETURN;
    END IF;
    PERFORM set_config('snigf.actor_id', actor::text, true);

    FOR who IN SELECT * FROM (VALUES
        ('NATURAL_PERSON', 'DEMO Seed Owner One', 'DEMO-ID-OWNER-1', NULL, NULL, NULL),
        ('NATURAL_PERSON', 'DEMO Seed Buyer Two', 'DEMO-ID-BUYER-2', NULL, NULL, NULL),
        ('NATURAL_PERSON', 'DEMO Seed Rival Three', 'DEMO-ID-RIVAL-3', NULL, NULL, NULL),
        ('LEGAL_PERSON', 'DEMO Seed Bank', 'DEMO-RC-BANK', NULL, NULL, NULL),
        ('NOTARY', 'DEMO Seed Notary', 'DEMO-ID-NOTARY', 'DEMO Chamber of Notaries', 'DEMO-N-001', 'DEMO Yaounde')
    ) AS t(kind, full_name, identity_number, chamber, registration, jurisdiction) LOOP
        PERFORM register_party_create(office_id, who.kind, who.full_name, 'NATIONAL_ID', who.identity_number, who.chamber, who.registration,
                                      who.jurisdiction, NULL);
    END LOOP;
    holder := (SELECT id FROM register_party p WHERE p.full_name = 'DEMO Seed Owner One');

    geometry := normalize_parcel_geometry(
        '{"type":"Polygon","coordinates":[[[790000,420000],[790100,420000],[790100,420100],[790000,420100],[790000,420000]]]}', 32632);
    INSERT INTO parcel (id, reference, office_id, current_version_no, created_by)
    VALUES (parcel_id, 'DEMO-PCL-YDE1-2026-900001-Z', office_id, 1, actor);
    INSERT INTO parcel_version (id, parcel_id, version_no, geom, area_m2, source_srid, source_geojson_sha256, locality, created_by)
    VALUES (version_id, parcel_id, 1, geometry.geom, geometry.area_m2, 32632, repeat('a', 64), 'Synthetic register fixture', actor);

    PERFORM register_propose(office_id, NULL, 'CREATION', 0, 'SYNTHETIC_DEMO', 'DEMO-SEED-TITLE-1', 'Synthetic DEMO title from the development seed',
                             ARRAY['DEMO-SEED-EVIDENCE-1'], NULL, NULL, NULL, geometry.area_m2, version_id,
                             jsonb_build_array(jsonb_build_object('party', holder, 'interest', 'OWNERSHIP', 'shareNum', 1, 'shareDen', 1)), NULL);
END;
$seed$;
SQL
echo "Done. Sign any waiting proposal at #/register as conservator.yde@demo.invalid (docs/guides/land-register.md)."
