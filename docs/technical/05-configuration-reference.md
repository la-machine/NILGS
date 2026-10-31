# 5. Configuration reference

*Copyright (c) 2026 FLYT. Author: Pythagore.*

Rules ([ADR-014](../adr/014-configuration-and-secrets.md)):

- **Settings** live in `.env`: everything that can differ between machines or
  environments, and nothing that grants access. `.env.example` documents each one with
  its development value; `infra/scripts/dev-setup.sh` copies it to `.env` and later
  appends new settings.
- **Secrets** are files in `infra/secrets/`, mounted as Docker secrets in
  `/run/secrets/<name>`. They are never environment variables and never in Git.
- **No silent defaults.** Compose uses `${NAME:?set in .env}`, Spring binds to validated
  records, and scripts check with `: "${NAME:?}"`. A missing value stops startup.
- **`.env` is data, never code.** Scripts read it with `load_env` (`dev-pki.sh`); CI reads
  single values with `sed`. Values may contain spaces and need no quotes.

## Settings (`.env`)

"Read by" names the files that consume the value. Compose passes it into containers as
an environment variable of the same name.

### Runtime and endpoints

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_PROFILE` | `dev` | Spring profile of the app container: `dev`, `staging` or `prod` (`test` is for automated tests) | compose (`SPRING_PROFILES_ACTIVE`) |
| `SNIGF_PUBLIC_HOSTNAME` | `localhost` | Host name in the browser; the proxy certificate is issued for it | compose, `dev-pki.sh`, probe |
| `SNIGF_HTTPS_PORT` | `47000` | Proxy HTTPS port, published on `127.0.0.1` only | compose, Caddyfile, probe |
| `SNIGF_APP_PORT` | `47080` | Backend API port (mutual TLS) | compose, `application.yaml`, Caddyfile, probe, `vite.config.ts` |
| `SNIGF_MANAGEMENT_PORT` | `47081` | Actuator port, container loopback only | compose, `application.yaml`, probe, `backend/Dockerfile` health check |
| `SNIGF_DB_PORT` | `47432` | PostgreSQL port | compose, `application.yaml`, probe |
| `SNIGF_VITE_PORT` | `47173` | Vite dev server (HTTPS) | `vite.config.ts` |
| `SNIGF_VITE_PREVIEW_PORT` | `47174` | Vite preview (HTTPS) | `vite.config.ts` |
| `SNIGF_PROVIDER_CALLBACK_PORT` | `47090` | The proxy's listener for payment provider callbacks (mutual TLS, ADR-037), on the internal network `provider-in` only; never published | compose, Caddyfile |
| `SNIGF_PAYMENT_SIM_PORT` | `47091` | The DEMO payment simulator's charge endpoint (mutual TLS), on `provider-out` only | compose, `application.yaml`, `simulator.mjs` |

### Transport security

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_PROXY_CLIENT_CN` | `snigf-web` | Certificate CN the app admits (the proxy's identity) | compose, `application-transport-security.yaml`, `dev-pki.sh` |
| `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` | `X-Snigf-Client-Address` | Header in which the proxy passes the browser's address | compose, `application-transport-security.yaml`, Caddyfile |
| `SNIGF_PROXY_PROVIDER_CLIENT_HEADER` | `X-Snigf-Provider-Client` | Header in which the proxy's provider listener passes the provider's verified certificate subject; removed on the browser listener, believed only from the proxy (ADR-037) | compose, `application.yaml`, Caddyfile |
| `SNIGF_PKI_ORGANIZATION` | `FLYT` | `O=` of development certificates | `dev-pki.sh` |
| `SNIGF_PKI_ROOT_DAYS` | `3650` | Development root CA lifetime | `dev-pki.sh` |
| `SNIGF_PKI_INTERMEDIATE_DAYS` | `730` | Services CA lifetime | `dev-pki.sh` |
| `SNIGF_PKI_LEAF_DAYS` | `90` | Service certificate lifetime | `dev-pki.sh` |
| `SNIGF_PKI_RENEW_BEFORE_DAYS` | `14` | Re-issue a leaf expiring within this many days | `dev-pki.sh` |

### Key custody (OpenBao)

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_BAO_PORT` | `47820` | OpenBao API (mutual TLS 1.3) | compose, `application-key-custody.yaml`, `bao-init.sh`, `start-openbao.sh`, probe |
| `SNIGF_BAO_CLUSTER_PORT` | `47821` | Raft cluster port | compose, `start-openbao.sh` |
| `SNIGF_BAO_LOG_LEVEL` | `info` | OpenBao log level; never `trace` outside debugging (it logs bodies) | compose, `start-openbao.sh` |
| `SNIGF_BAO_UNSEAL_SHARES` | `5` | Shamir shares created at initialisation | compose, `bao-init.sh` |
| `SNIGF_BAO_UNSEAL_THRESHOLD` | `3` | Shares needed to unseal or to generate a root token | compose, `bao-init.sh` |
| `SNIGF_BAO_INIT_TIMEOUT` | `120` | Seconds `bao-init` waits for OpenBao | compose, `bao-init.sh` |
| `SNIGF_BAO_TRANSIT_MOUNT` | `transit` | Mount path of the transit engine | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_BAO_AUTH_MOUNT` | `cert` | Mount path of certificate login | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_BAO_APP_ROLE` | `snigf-app` | Application login role and policy name | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_BAO_APP_CLIENT_CN` | `snigf-app` | CN the application's OpenBao certificate must carry | compose, `dev-pki.sh`, `bao-init.sh` |
| `SNIGF_BAO_ADMIN_CLIENT_CN` | `snigf-bao-admin` | CN of the provisioning job's transport certificate | `dev-pki.sh` |
| `SNIGF_BAO_HEALTH_CLIENT_CN` | `snigf-bao-health` | CN of the health check's transport certificate | `dev-pki.sh` |
| `SNIGF_BAO_TOKEN_TTL` | `15m` | Application token lifetime (renewed at two thirds) | compose, `bao-init.sh` |
| `SNIGF_BAO_TOKEN_MAX_TTL` | `1h` | Maximum token lifetime | compose, `bao-init.sh` |
| `SNIGF_BAO_CONNECT_TIMEOUT` | `5s` | Application connect timeout to OpenBao | compose, `application-key-custody.yaml` |
| `SNIGF_BAO_REQUEST_TIMEOUT` | `10s` | Application request timeout to OpenBao | compose, `application-key-custody.yaml` |

### Keys

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_KEY_AUDIT_CHECKPOINT` | `snigf-audit-checkpoint` | Ed25519 key that signs audit checkpoints | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_KEY_RECORD_SIGNING` | `snigf-record-signing` | Ed25519 key that signs records | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_DOCUMENT_ISSUANCE` | `snigf-document-issuance` | Ed25519 key that signs the manifest of every document SNIGF issues (ADR-039); never the record key | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_DATA_ENCRYPTION` | `snigf-data-encryption` | AES-256-GCM key that wraps data keys (derived per context) | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_KEY_PAYMENT_CALLBACK` | `snigf-payment-callback` | HMAC key for payment callbacks | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_PASSWORD_PEPPER` | `snigf-password-pepper` | HMAC key that peppers password hashes (never rotated automatically) | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_KEY_BLIND_INDEX` | `snigf-blind-index` | HMAC key for searchable blind indexes (never rotated automatically) | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_ROTATE_SIGNING` | `2160h` | Automatic rotation of the signing keys: checkpoint, record and document issuance (`0` = off); older versions stay available, so earlier signatures still verify | compose, `bao-init.sh` |
| `SNIGF_KEY_ROTATE_DATA_ENCRYPTION` | `2160h` | Automatic rotation of the data key-wrapping key | compose, `bao-init.sh` |
| `SNIGF_KEY_ROTATE_PAYMENT_CALLBACK` | `8760h` | Automatic rotation of the payment callback key | compose, `bao-init.sh` |

### Authentication and sessions

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_PASSWORD_MIN_LENGTH` | `12` | Minimum password length (P7); at least 12 outside tests | compose, `application.yaml` |
| `SNIGF_PASSWORD_MAX_LENGTH` | `128` | Maximum password length (caps hashing cost) | compose, `application.yaml` |
| `SNIGF_PASSWORD_ARGON2_MEMORY_KIB` | `19456` | Argon2id memory per hash; at least 19456 outside tests (OWASP) | compose, `application.yaml` |
| `SNIGF_PASSWORD_ARGON2_ITERATIONS` | `2` | Argon2id passes; at least 2 outside tests | compose, `application.yaml` |
| `SNIGF_PASSWORD_ARGON2_PARALLELISM` | `1` | Argon2id lanes | compose, `application.yaml` |
| `SNIGF_PASSWORD_MAX_CONCURRENT_HASHES` | `8` | Hashes computed at once (each uses the Argon2 memory) | compose, `application.yaml` |
| `SNIGF_PASSWORD_HASH_QUEUE_TIMEOUT` | `2s` | Wait for a hashing slot before answering 503 `BUSY` | compose, `application.yaml` |
| `SNIGF_LOGIN_MAX_FAILURES` | `5` | Consecutive wrong passwords that lock an account (P6) | compose, `application.yaml` |
| `SNIGF_LOGIN_LOCK_DURATION` | `15m` | Lock duration (P6) | compose, `application.yaml` |
| `SNIGF_RATE_LOGIN_PER_MINUTE` | `20` | Login attempts per client address per minute (P6) | compose, `application.yaml` |
| `SNIGF_RATE_REGISTER_PER_HOUR` | `10` | Registrations per client address per hour | compose, `application.yaml` |
| `SNIGF_RATE_MAX_TRACKED_CLIENTS` | `10000` | Client addresses remembered by the throttle (least recently seen dropped) | compose, `application.yaml` |
| `SNIGF_SESSION_IDLE_TIMEOUT` | `30m` | Session idle limit (P5); not above the absolute limit | compose, `application.yaml` |
| `SNIGF_SESSION_ABSOLUTE_TIMEOUT` | `8h` | Session absolute limit since login (P5) | compose, `application.yaml` |
| `SNIGF_SESSION_COOKIE_NAME` | `__Host-snigf-session` | Session cookie; `__Host-` prefix required outside tests | compose, `application.yaml` |
| `SNIGF_SESSION_COOKIE_SAME_SITE` | `Strict` | `Strict` or `Lax` | compose, `application.yaml` |
| `SNIGF_SESSION_CLEANUP_CRON` | `0 */5 * * * *` | Deletion of expired sessions (Spring cron) | compose, `application.yaml` |
| `SNIGF_CSRF_COOKIE_NAME` | `__Host-snigf-xsrf` | Cookie holding the CSRF token; `__Host-` prefix required outside tests | compose, `application.yaml` |
| `SNIGF_CSRF_HEADER_NAME` | `X-XSRF-TOKEN` | Header in which the page sends the token back | compose, `application.yaml` |

### Second factor (WebAuthn)

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_WEBAUTHN_RP_ID` | `localhost` | Relying-party id: the host users see; credentials are bound to it, so changing it invalidates every registered authenticator | compose, `application.yaml` |
| `SNIGF_WEBAUTHN_RP_NAME` | `SNIGF-NILGS demo` | Name the authenticator shows when registering | compose, `application.yaml` |
| `SNIGF_WEBAUTHN_ORIGINS` | `https://localhost:47000,https://localhost:47173` | Exact origins allowed to run ceremonies; each host must be the relying-party id or a subdomain; https outside tests | compose, `application.yaml` |
| `SNIGF_WEBAUTHN_TIMEOUT` | `2m` | How long a registration or verification may take (also the server-side ceremony lifetime) | compose, `application.yaml` |
| `SNIGF_WEBAUTHN_ATTESTATION` | `none` | `none`, `indirect` or `direct` (direct needs a device trust list) | compose, `application.yaml` |
| `SNIGF_WEBAUTHN_MIN_AUTHENTICATORS` | `2` | Authenticators a staff member registers before enrolment completes; at least 2 outside tests (ADR-016) | compose, `application.yaml` |
| `SNIGF_MFA_RECOVERY_CODES` | `10` | One-time recovery codes issued at enrolment (5 to 20) | compose, `application.yaml` |

### Administration (ADR-025)

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_ADMIN_REQUEST_TTL` | `7d` | How long a role change or special grant request waits for a second administrator; after that it can only be rejected or requested again. Between `1h` and `30d` | compose, `application.yaml` |

### Parcels and map views (ADR-029)

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_PARCEL_REFERENCE_ZONE` | `Africa/Douala` | Time zone whose calendar year goes into a new parcel reference (`DEMO-PCL-<office>-<year>-<seq>-<check>`) | compose, `application.yaml` |
| `SNIGF_PARCEL_PAGE_SIZE` | `50` | Parcels per search page (5 to 200) | compose, `application.yaml` |
| `SNIGF_CONFLICT_MIN_OVERLAP_M2` | `1.00` | Smallest intersection counted as an overlap, m² on the spheroid (0.01 to 1000; MVP-04) | compose, `application.yaml` |
| `SNIGF_CONFLICT_DUPLICATE_SHARE` | `0.95` | Share of each parcel an overlap must cover to be a possible duplicate (0.5 to 1) | compose, `application.yaml` |
| `SNIGF_APPLICATION_TYPES` | `classpath:application-types.json` | The application type catalogue (a Spring resource; `file:/...` for a mounted file); a changed definition becomes a new version at startup ([guide](../guides/applications.md)) | compose, `application.yaml` |
| `SNIGF_APPLICATION_REFERENCE_ZONE` | `Africa/Douala` | Time zone whose calendar year goes into application references | compose, `application.yaml` |
| `SNIGF_APPLICATION_PAGE_SIZE` | `20` | Applications per page (5 to 200) | compose, `application.yaml` |
| `SNIGF_APPLICATION_MAX_DRAFTS` | `20` | Drafts one applicant may hold at once (1 to 100) | compose, `application.yaml` |
| `SNIGF_REPORTING_TARGET_COMPLETENESS_REVIEW` | `P5D` | DEMO target of the completeness review (ISO-8601 duration, above zero, at most a year; Baseline: 5 days); past it, a case is overdue in the workload report ([guide](../guides/tracking-and-workload.md)) | compose, `application.yaml` |
| `SNIGF_REPORTING_TARGET_INFO_REQUESTED` | `P30D` | DEMO target of the wait for the applicant's response | compose, `application.yaml` |
| `SNIGF_REPORTING_TARGET_UNDER_REVIEW` | `P10D` | DEMO target of the substantive review | compose, `application.yaml` |
| `SNIGF_REPORTING_TARGET_PENDING_DECISION` | `P5D` | DEMO target of the wait for the decision | compose, `application.yaml` |
| `SNIGF_REPORTING_DURATION_WINDOW` | `P90D` | The period the stage durations cover, back from now (above zero, at most ten years) | compose, `application.yaml` |
| `SNIGF_REPORTING_PAGE_SIZE` | `50` | Cases per page of the workload queue (5 to 200); the interface pages by the same number | compose, `application.yaml` |
| `SNIGF_PAYMENT_FEES` | `classpath:payment-fees.json` | The DEMO fee catalogue, one fee per application type in XAF, applied at start-up and versioned ([guide](../guides/payments.md)) | compose, `application.yaml` |
| `SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE` | `true` | The payment gate: completeness is not accepted until the DEMO fee is paid (service and database) | compose, `application.yaml` |
| `SNIGF_PAYMENT_PENDING_TTL` | `PT24H` | How long a payment may stay pending before it expires (`PT1M` to `P7D`) | compose, `application.yaml` |
| `SNIGF_PAYMENT_PROVIDER` | `DEMO_SIMULATOR` | The payment provider; the only approved value. Anything else stops the start (and the database refuses it) | compose, `application.yaml` |
| `SNIGF_PAYMENT_CALLBACK_MAX_SKEW` | `PT5M` | How far a callback's timestamp may be from now (`PT10S` to `PT15M`); older is a replay | compose, `application.yaml` |
| `SNIGF_PAYMENT_PROVIDER_CLIENT_CNS` | `snigf-payment-sim` | Provider certificate names the proxy may vouch for at the callback (comma separated) | compose, `application.yaml` |
| `SNIGF_PAYMENT_EXPIRY_CRON` | `0 */5 * * * *` | When pending payments past their expiry are marked expired and audited (expiry itself is decided from `expires_at`) | compose, `application.yaml` |
| `SNIGF_PAYMENT_PAGE_SIZE` | `50` | Rows per page of the finance lists (5 to 200) | compose, `application.yaml` |
| `SNIGF_PAYMENT_SIMULATOR_CONNECT_TIMEOUT` / `SNIGF_PAYMENT_SIMULATOR_REQUEST_TIMEOUT` | `PT3S` / `PT5S` | Timeouts of the app's charge call to the simulator | compose, `application.yaml` |
| `SNIGF_PAYMENT_APP_CLIENT_CN` | `snigf-app-payments` | The app's certificate name towards the simulator; the simulator admits only it | compose, `dev-pki.sh`, `simulator.mjs` |
| `SNIGF_PAYMENT_SIM_CLIENT_CN` | `snigf-payment-sim` | The simulator's certificate name, towards the proxy's provider listener and OpenBao | compose, `dev-pki.sh`, `bao-init.sh` |
| `SNIGF_BAO_PAYMENT_SIM_ROLE` | `snigf-payment-sim` | The simulator's OpenBao login role and policy: HMAC with the payment callback key, nothing else | compose, `bao-init.sh`, `simulator.mjs` |
| `SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS` | `1500` | How long the simulator waits before sending its callbacks | compose, `simulator.mjs` |
| `SNIGF_ISSUANCE_VERIFY_BASE_URL` | `https://localhost:47000` | The public address the QR code and the PDF name (`<base>/#/verify/<number>`); https only, or the app refuses to start ([guide](../guides/issued-documents.md)) | compose, `application.yaml` |
| `SNIGF_ISSUANCE_MAX_UPLOAD_BYTES` | `2097152` | The largest PDF accepted for verification (1 KB to 5 MB) | compose, `application.yaml` |
| `SNIGF_RATE_VERIFY_PER_MINUTE` | `20` | Public verifications per client address per minute (1 to 600); beyond, 429 with `Retry-After` | compose, `application.yaml` |
| `SNIGF_ISSUANCE_PAGE_SIZE` | `50` | Incidents per page of the document officer's queue (5 to 200) | compose, `application.yaml` |
| `SNIGF_REGISTER_LOCK_REVIEW_AFTER` | `P30D` | How long after a deposit a holding lock is due for review (ISO-8601). A lock never expires on its own (ADR-040) | compose, `application.yaml` |
| `SNIGF_REGISTER_OVERDUE_CRON` | `0 15 * * * *` | When overdue locks are recorded for review (Spring cron; `-` turns it off) | compose, `application.yaml` |
| `SNIGF_REGISTER_PAGE_SIZE` | `20` | Entries per page in the register's lists (5 to 200) | compose, `application.yaml` |
| `SNIGF_DOCUMENT_DIRECTORY` | `../var/documents` | Where document files are kept when running from the IDE; compose mounts the volume `document-data` at `/var/lib/snigf/documents` | `application.yaml` |
| `SNIGF_DOCUMENT_MAX_BYTES` | `10485760` | Largest document accepted, in bytes (Baseline: 10 MB DEMO limit; at most 100 MB) | compose, `application.yaml` (also the servlet's `max-file-size`) |
| `SNIGF_DOCUMENT_MAX_VERSIONS` | `10` | Versions one document may have (1 to 100) | compose, `application.yaml` |
| `SNIGF_DOCUMENT_MAX_PIXELS` | `50000000` | Largest image accepted, width × height (against decompression bombs) | compose, `application.yaml` |
| `SNIGF_DOCUMENT_ORPHAN_GRACE` | `PT1H` | How old an unreferenced file must be before the cleanup removes it | compose, `application.yaml` |
| `SNIGF_DOCUMENT_CLEANUP_CRON` | `0 17 * * * *` | When the orphan cleanup runs (six-field cron; `-` disables it) | compose, `application.yaml` |
| `SNIGF_PROXY_MAX_BODY_BYTES` | `10600000` | Largest request body the proxy forwards to the API (a document and its multipart envelope); also the servlet's `max-request-size` | compose (web and app), Caddyfile, `application.yaml` |
| `SNIGF_MAP_DATA_DIRECTORY` | `../var/maps` | Map data for a backend run from the IDE (relative to `backend/`); the container always reads the read-only volume `map-data` at `/maps` | `application.yaml` |
| `SNIGF_MAP_EXTERNAL_PROVIDERS` | `none` | External map providers; `none` is the only accepted value, the application refuses to start otherwise | compose, `application.yaml` |
| `SNIGF_MAPS_OSM_AREA` | `cameroon` | Geofabrik area built into the geographic view | `maps-build-geographic.sh` |
| `SNIGF_MAPS_GEOGRAPHIC_MAXZOOM` | `14` | Highest zoom of the vector tiles (12 to 15) | `maps-build-geographic.sh` |
| `SNIGF_MAPS_FONTS_URL` | openmaptiles/fonts v2.0 `noto-sans.zip` | Label glyph archive (Noto Sans, SIL OFL 1.1), https only | compose (`maps-fetch`) |
| `SNIGF_MAPS_FONTS_SHA256` | `d1173165…` | Pinned SHA-256 of that archive: checked after download and again before extraction | compose, `maps-build-geographic.sh` |
| `SNIGF_MAPS_IMPORT_DIRECTORY` | `./var/maps-import` | Folder where licensed imagery is placed for import; mounted read-only into `maps-tools` | compose |
| `SNIGF_MAPS_SYNTHETIC_BOUNDS` | `11.42,3.80,11.60,3.95` | Area of the synthetic test imagery (west, south, east, north, degrees) | `maps-synthetic-imagery.sh` |
| `SNIGF_MAPS_SYNTHETIC_RESOLUTION` | `4` | Metres per pixel of the synthetic imagery | `maps-synthetic-imagery.sh` |
| `SNIGF_MAPS_SATELLITE_COUNTRY` | `CMR` | Country of the national satellite base (ISO 3166 alpha-3; outline from Natural Earth) | compose (`maps-fetch`) |
| `SNIGF_MAPS_SATELLITE_BUFFER_KM` | `2` | Margin around the border, so parcels at the border are covered | compose (`maps-fetch`) |
| `SNIGF_MAPS_SATELLITE_FIRST_PLACES` | twelve towns (`name:lon,lat;...`) | Tiles holding these towns are fetched first | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_STAC_URL` | Planetary Computer STAC | Catalogue of the Sentinel-2 archive (https) | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_TOKEN_URL` | Planetary Computer SAS token API | Anonymous read-only tokens for that archive; empty for an open archive | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_FROM` / `SNIGF_MAPS_SENTINEL_TO` | `2024-11-01` / empty (today) | Period searched | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_MAX_CLOUD` | `40` | Highest cloud cover of a scene considered (%) | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_MAX_SCENES` | `4` | Scenes combined at most per tile to fill cloud gaps | compose (`maps-fetch`) |
| `SNIGF_MAPS_SENTINEL_TILES` | empty | Only these Sentinel-2 tiles (a trial, e.g. `32NQK` for Yaoundé) | compose (`maps-fetch`) |
| `SNIGF_MAPS_AERIAL_CATALOGUE_URL` | `https://api.openaerialmap.org` | Catalogue of the aerial images | compose (`maps-fetch`) |
| `SNIGF_MAPS_AERIAL_IMAGES` | three OpenAerialMap ids (Douala, Ngaoundéré) | Aerial images drawn as detail | compose (`maps-fetch`) |
| `SNIGF_MAPS_AERIAL_LICENCES` | `CC-BY 4.0` | Licences accepted (`;`-separated); any other is refused | compose (`maps-fetch`) |
| `SNIGF_MAPS_AERIAL_MAX_ZOOM` | `20` | Highest zoom of an aerial detail (15 to 22) | `maps-build-satellite.sh` |

### Audit trail

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_AUDIT_CHECKPOINT_DIR` | `../var/audit-checkpoints` | Checkpoint directory for a backend run from the IDE (relative to `backend/`); the container uses `/var/lib/snigf/audit-checkpoints` (`backend/Dockerfile`) | `application.yaml` |
| `SNIGF_AUDIT_CHECKPOINT_CRON` | `0 0 2 * * *` | Daily checkpoint, Spring cron with six fields; `-` (tests only) disables | compose, `application.yaml` |
| `SNIGF_AUDIT_VERIFY_CRON` | `0 30 2 * * *` | Daily full verification | compose, `application.yaml` |
| `SNIGF_AUDIT_SCHEDULE_ZONE` | `Africa/Douala` | Time zone of both schedules | compose, `application.yaml` |
| `SNIGF_AUDIT_VERIFY_ON_STARTUP` | `true` | Verify the whole chain in the background at startup | compose, `application.yaml` |
| `SNIGF_AUDIT_MAX_DETAILS_BYTES` | `8192` | Largest `details` JSON of one event (256 to 65536) | compose, `application.yaml` |
| `SNIGF_AUDIT_VERIFY_FETCH_SIZE` | `1000` | Rows per round trip while verifying (10 to 100000) | compose, `application.yaml` |

### Database server and resource limits

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_DB_LOG_CONNECTIONS` | `on` | Log every connection and disconnection | compose, `start-postgres.sh` |
| `SNIGF_APP_MEMORY` / `SNIGF_APP_CPUS` / `SNIGF_APP_PIDS` | `1g` / `2` / `512` | app container limits | compose |
| `SNIGF_DB_MEMORY` / `SNIGF_DB_CPUS` / `SNIGF_DB_PIDS` | `1g` / `2` / `256` | db container limits | compose |
| `SNIGF_WEB_MEMORY` / `SNIGF_WEB_CPUS` / `SNIGF_WEB_PIDS` | `256m` / `1` / `128` | web container limits | compose |
| `SNIGF_MIGRATE_MEMORY` | `512m` | migrate container memory | compose |
| `SNIGF_BAO_MEMORY` / `SNIGF_BAO_CPUS` / `SNIGF_BAO_PIDS` | `512m` / `1` / `256` | openbao container limits | compose |
| `SNIGF_PAYMENT_SIM_MEMORY` / `SNIGF_PAYMENT_SIM_CPUS` / `SNIGF_PAYMENT_SIM_PIDS` | `128m` / `0.5` / `64` | payment-sim container limits | compose |
| `SNIGF_E2E_MEMORY` / `SNIGF_E2E_CPUS` / `SNIGF_E2E_PIDS` / `SNIGF_E2E_SHM` | `2g` / `2` / `1024` / `512m` | e2e container limits and shared memory for Chromium ([guide](../guides/end-to-end-testing.md)) | compose |
| `SNIGF_MAPS_MEMORY` / `SNIGF_MAPS_CPUS` / `SNIGF_MAPS_PIDS` | `6g` / `4` / `1024` | limits of every map tool job (profile `maps`, [guide](../guides/map-data.md)) | compose |
| `SNIGF_MAPS_JAVA_HEAP` | `3g` | Planetiler's Java heap | compose |

## Variables set by the stack, not by `.env`

| Variable | Set by | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | compose (from `SNIGF_PROFILE`) | Active profile |
| `SNIGF_DB_URL` | compose | JDBC URL inside the stack (`db` host) |
| `SNIGF_BAO_URL` | compose | OpenBao URL inside the stack (`openbao` host), for the app and the payment simulator |
| `SNIGF_PAYMENT_SIMULATOR_URL` | compose | The simulator's URL inside the stack (`https://payment-sim:<SNIGF_PAYMENT_SIM_PORT>`); from the IDE, `https://localhost:<port>` |
| `SNIGF_PAYMENT_SIM_CALLBACK_URL` | compose | Where the simulator sends callbacks: the proxy's provider listener (`https://web:<SNIGF_PROVIDER_CALLBACK_PORT>/api/v1/payments/callbacks/demo-simulator`) |
| `SNIGF_DB_CLIENT_KEY` | `backend/Dockerfile` | Private copy of the database client key (`/run/snigf-keys`, 0600) |
| `SNIGF_AUDIT_CHECKPOINT_DIR` | `backend/Dockerfile` | Container checkpoint directory (overrides `.env`) |
| `SNIGF_MAP_DATA_DIRECTORY` | compose | `/maps`, the read-only volume `map-data` (overrides `.env`) |
| `SNIGF_SECRETS_DIR` | you, for an IDE run | Absolute path of `infra/secrets` (default `/run/secrets`); never a path with `..` |
| `SNIGF_ENV_FILE` | you, optionally | `.env` location for an IDE run (default `../.env`) |

## Application properties (`snigf.*`)

Bound and validated in `SnigfProperties` (shared) and `AuditProperties` (audit).

| Property | From | Rule |
| --- | --- | --- |
| `snigf.environment` | profile file | DEV, TEST, STAGING, PROD; must match the active profile |
| `snigf.demo-mode` | profile file | false in prod |
| `snigf.security.transport.require-client-certificate` | `application.yaml`, test profile | true outside tests |
| `snigf.security.transport.allowed-client-common-names` | `SNIGF_PROXY_CLIENT_CN` | not empty outside tests |
| `snigf.security.transport.client-address-header` | `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` | `X-...`; required outside tests |
| `snigf.keys.provider` | `application.yaml` | OPENBAO; EPHEMERAL only in tests |
| `snigf.keys.openbao.*` | `application-key-custody.yaml` | https URL, TLS 1.3 bundle |
| `snigf.keys.names.*` | `SNIGF_KEY_*` | six distinct names |
| `snigf.audit.*` | `SNIGF_AUDIT_*` | schedules enabled outside tests; absolute directory outside dev |
| `snigf.parcel.*` | `SNIGF_PARCEL_*` | `ParcelProperties`: a valid time zone; page size 5 to 200 |
| `snigf.document.*` | `SNIGF_DOCUMENT_*` | `DocumentProperties`: directory, limits, grace period and cleanup schedule; the directory must be writable at startup |
| `snigf.payment.*` | `SNIGF_PAYMENT_*`, `SNIGF_PROXY_PROVIDER_CLIENT_HEADER` | `PaymentProperties`: provider `DEMO_SIMULATOR` only; pending time to live and callback skew within bounds; the simulator channel `HTTPS` (URL, SSL bundle `snigf-payment-provider`, timeouts), `IN_PROCESS` refused outside the test profile; the fee file checked strictly at start-up |
| `snigf.application.*` | `SNIGF_APPLICATION_*` | `ApplicationProperties`; the catalogue file is checked strictly at startup (unknown properties refused) |
| `snigf.conflict.*` | `SNIGF_CONFLICT_*` | `ConflictProperties` and `ConflictPolicy`: within the database's bounds, applied at startup |
| `snigf.maps.*` | `SNIGF_MAP_*` | `MapProperties`: a data directory; external providers exactly `none` |
| `snigf.identity.*` | `SNIGF_PASSWORD_*`, `SNIGF_LOGIN_*`, `SNIGF_RATE_*`, `SNIGF_SESSION_ABSOLUTE_TIMEOUT`, `SNIGF_CSRF_*`, `SNIGF_WEBAUTHN_*`, `SNIGF_ADMIN_REQUEST_TTL` | checked by `IdentityConfiguration` (see chapter 8) |
| `server.servlet.session.*`, `spring.session.*` | `SNIGF_SESSION_*` | cookie Secure, HttpOnly, `__Host-`, SameSite Strict or Lax outside tests |

## Secrets (`infra/secrets/`)

Generated by `dev-setup.sh` (passwords) and `dev-pki.sh` (keys and certificates). Each is
mounted only into the services listed.

| Secret | Content | Mounted into |
| --- | --- | --- |
| `db_superuser_password` | PostgreSQL superuser password | db |
| `db_migrator_password` | `snigf_migrator` password | db, migrate |
| `db_app_password` | `snigf_app` password | db, app, security-probe |
| `db_auditor_password` | `snigf_auditor` password | db |
| `pki_ca_chain_crt` | Services CA + root CA (trust anchor) | every service |
| `web_tls_crt`, `web_tls_key` | Proxy server certificate | web |
| `web_client_crt`, `web_client_key` | Proxy client identity towards the app | web, security-probe |
| `app_tls_crt`, `app_tls_key` | App server certificate | app |
| `db_tls_crt`, `db_tls_key` | Database server certificate | db |
| `app_db_client_crt`, `app_db_client_key`, `app_db_client_key_pk8` | App identity towards the database (PEM and DER PKCS#8) | app (crt, pk8), security-probe (crt, key) |
| `migrator_db_client_crt`, `migrator_db_client_key`, `migrator_db_client_key_pk8` | Migrator identity towards the database | migrate (crt, pk8), security-probe (crt, key) |
| `bao_tls_crt`, `bao_tls_key` | OpenBao server certificate | openbao |
| `app_bao_client_crt`, `app_bao_client_key` | App identity and login towards OpenBao | app, security-probe |
| `bao_admin_client_crt`, `bao_admin_client_key` | Provisioning job's transport identity | bao-init |
| `bao_health_client_crt`, `bao_health_client_key` | Health check's transport identity | openbao |
| `web_provider_tls_crt`, `web_provider_tls_key` | The proxy's server certificate on its provider listener (name `web`) | web |
| `payment_sim_tls_crt`, `payment_sim_tls_key` | The DEMO payment simulator's server certificate (name `payment-sim`) | payment-sim |
| `payment_sim_client_crt`, `payment_sim_client_key` | The simulator's identity towards the proxy's provider listener and its OpenBao login | payment-sim, security-probe |
| `app_provider_client_crt`, `app_provider_client_key` | The app's identity towards the simulator | app |
| `demo_accounts` | **Development only**: the demonstration accounts and their passwords, written by `dev-seed-demo.sh` ([guide](../guides/demo-accounts.md)); one line per account, `email|password|role|office` | nothing: read by people |

Not mounted anywhere:

- `pki/`: CA private keys and working files;
- `pki_root_ca_crt`: for your browser and `curl --cacert`.
