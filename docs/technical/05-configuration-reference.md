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

### Transport security

| Setting | Development value | Meaning | Read by |
| --- | --- | --- | --- |
| `SNIGF_PROXY_CLIENT_CN` | `snigf-web` | Certificate CN the app admits (the proxy's identity) | compose, `application-transport-security.yaml`, `dev-pki.sh` |
| `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` | `X-Snigf-Client-Address` | Header in which the proxy passes the browser's address | compose, `application-transport-security.yaml`, Caddyfile |
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
| `SNIGF_KEY_DATA_ENCRYPTION` | `snigf-data-encryption` | AES-256-GCM key that wraps data keys (derived per context) | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_KEY_PAYMENT_CALLBACK` | `snigf-payment-callback` | HMAC key for payment callbacks | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_PASSWORD_PEPPER` | `snigf-password-pepper` | HMAC key that peppers password hashes (never rotated automatically) | compose, `application-key-custody.yaml`, `bao-init.sh`, probe |
| `SNIGF_KEY_BLIND_INDEX` | `snigf-blind-index` | HMAC key for searchable blind indexes (never rotated automatically) | compose, `application-key-custody.yaml`, `bao-init.sh` |
| `SNIGF_KEY_ROTATE_SIGNING` | `2160h` | Automatic rotation of both signing keys (`0` = off) | compose, `bao-init.sh` |
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

## Variables set by the stack, not by `.env`

| Variable | Set by | Meaning |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | compose (from `SNIGF_PROFILE`) | Active profile |
| `SNIGF_DB_URL` | compose | JDBC URL inside the stack (`db` host) |
| `SNIGF_BAO_URL` | compose | OpenBao URL inside the stack (`openbao` host) |
| `SNIGF_DB_CLIENT_KEY` | `backend/Dockerfile` | Private copy of the database client key (`/run/snigf-keys`, 0600) |
| `SNIGF_AUDIT_CHECKPOINT_DIR` | `backend/Dockerfile` | Container checkpoint directory (overrides `.env`) |
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
| `snigf.identity.*` | `SNIGF_PASSWORD_*`, `SNIGF_LOGIN_*`, `SNIGF_RATE_*`, `SNIGF_SESSION_ABSOLUTE_TIMEOUT`, `SNIGF_CSRF_*` | checked by `IdentityConfiguration` (see chapter 8) |
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

Not mounted anywhere:

- `pki/`: CA private keys and working files;
- `pki_root_ca_crt`: for your browser and `curl --cacert`.
