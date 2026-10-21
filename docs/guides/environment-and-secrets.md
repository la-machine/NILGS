# Environment, settings and secrets

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Every value that can differ between machines or environments is a **setting** in `.env`;
every value that grants access is a **secret file** in `infra/secrets/`. Nothing is
hard-coded and nothing falls back silently: Compose and the application refuse to start
when a setting is missing. No service listens on its product's default port.
Decisions: [ADR-014](../adr/014-configuration-and-secrets.md),
[ADR-015](../adr/015-non-default-ports.md).

Settings and secrets are kept apart because environment variables leak: `docker inspect`,
process listings, crash reports and CI logs all show them. Files mounted as Docker
secrets do not.

## Where

| File | Role |
| --- | --- |
| `.env.example` | Every setting, documented, with its development value. Versioned. No secrets. |
| `.env` | Your copy (git-ignored). Compose reads it automatically; the backend imports it when run from the IDE. |
| `infra/secrets/` | Passwords, private keys, certificates (git-ignored, directory mode 700). |
| `infra/scripts/dev-setup.sh` | Creates `.env`, appends new settings to an existing one, generates passwords, then runs `dev-pki.sh`. |
| `compose.yml` | Uses `${NAME:?set in .env}` for every setting; declares every secret under `secrets:`. |
| `backend/src/main/resources/application.yaml` | Imports `../.env` (IDE) and the secrets directory as a config tree. |
| `frontend/vite.config.ts` | Reads `SNIGF_*` from the repository's `.env` with `loadEnv`. |
| `.gitignore`, CI secret scan (gitleaks) | Keep secrets out of Git. |

## How it works

1. `dev-setup.sh` copies `.env.example` to `.env` on first run. On later runs it appends
   any `SNIGF_*` line that `.env` lacks, and never changes an existing value.
2. It generates each password with `openssl rand -hex 24` and strips the trailing CR/LF.
   It then runs `dev-pki.sh` for certificates and keys (see
   [transport security](transport-security.md)).
3. `docker compose up` substitutes `${NAME:?set in .env}`. A missing variable aborts with
   "set in .env" before any container starts.
4. Each container receives only the secrets listed under its `secrets:`. They appear as
   read-only files in `/run/secrets/<name>`.
5. Spring imports `/run/secrets/` as a *config tree*: the file `/run/secrets/db_app_password`
   becomes the property `db_app_password`, referenced as `${db_app_password:}`.

## How to do it again

### Add a setting

1. Add it to `.env.example` under the right section, with a comment saying what it does
   and its unit (`s`, `m`, `h`, bytes, ...). Name it `SNIGF_<AREA>_<NAME>`.
2. Pass it to each container that needs it in `compose.yml`, always with
   `${SNIGF_X:?set in .env}` and never with a default.
3. Use it:
   - **backend:** `${SNIGF_X}` in a YAML file, bound to a validated record in
     `SnigfProperties`, with no fallback;
   - **shell scripts:** read `.env` with `load_env` (never `. .env`), then
     `: "${SNIGF_X:?}"` at the top;
   - **frontend:** `required('SNIGF_X')` in `vite.config.ts`.
4. Run `bash infra/scripts/dev-setup.sh` to append it to your `.env`.
5. If it weakens security when set wrongly, add a rule to `EnvironmentGuard` and a test to
   `ProdStartupGuardTest`.
6. Add a `CHANGELOG.md` entry.

### Add a secret

1. Generate it in `dev-setup.sh` (passwords) or `dev-pki.sh` (keys and certificates). Never
   write it by hand or commit it.
2. Declare it under the top-level `secrets:` of `compose.yml`
   (`name: {file: ./infra/secrets/name}`). List it only under the services that need it.
3. Read it from `/run/secrets/name`:
   - **Spring:** use the config tree (`${name}`) or `file:${SNIGF_SECRETS_DIR:/run/secrets}/name`.
   - **Shell:** use `"$(cat /run/secrets/name)"`. Never put it in an argument other
     processes can see; send request bodies through stdin and headers through a 0600 file
     (see `infra/openbao/bao-init.sh`).
4. If a program insists on strict permissions (libpq, the PostgreSQL JDBC driver, OpenSSH),
   copy the file at start-up to a tmpfs with mode 0600. See `backend/docker-entrypoint.sh`
   and `infra/db/start-postgres.sh`.

### Choose a port

Take the next free number in the 47xxx block and record it in the README "Ports" table
and in `.env.example`. Publish only the public HTTPS port, and only on `127.0.0.1`; the
development overlay (`compose.dev.yml`) may publish others on `127.0.0.1` for the IDE.

### Sign a new file

Every authored file starts with the project notice in the file's comment syntax:

```text
SNIGF/NILGS - Copyright (c) 2026 FLYT.
Author: Pythagore.
```

Markdown uses `*Copyright (c) 2026 FLYT. Author: Pythagore.*` under the title. Images set
`org.opencontainers.image.vendor="FLYT"` and `org.opencontainers.image.authors="Pythagore"`.

## How to verify

```sh
docker compose config -q                      # every variable resolves
grep -L "FLYT" $(git ls-files '*.java' '*.sh' '*.yml' '*.yaml')   # must print nothing
git status --ignored infra/secrets .env        # listed as ignored, never as untracked
```

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| The database refused the right password | OpenSSL on Windows ends its output with CRLF; the file held `password\r` | `dev-setup.sh` rewrites each password file with `tr -d '\r\n'` |
| Backend from the IDE: "no password was provided" although the file exists | Spring's config tree **silently reads nothing** from a path containing `..` (`../infra/secrets`) | Pass an absolute path (`pwd -W` in Git Bash). `EnvironmentGuard` now stops startup with this hint |
| "private key file has group or world access" in the app container | Docker Desktop mounts secret files with mode 0777; the PostgreSQL JDBC driver refuses them | The entry point copies the key to `/run/snigf-keys` (tmpfs) with mode 0600 |
| Same message on Windows from the IDE ("BUILTIN\Administrateurs has read access") | The driver checks Windows ACLs, which are inherited from the folder | `dev-pki.sh` applies `icacls /inheritance:r /grant:r <you>:(F)` to private keys |
| A new setting is missing after `git pull` | `.env` predates it | Re-run `dev-setup.sh`: it appends missing settings |
| `.env: line N: 0: command not found` | Scripts sourced `.env` as shell code, so a value with spaces (a cron expression) ran as a command, and any line could run code | Scripts read `.env` as data with `load_env` (`dev-pki.sh`). Values with spaces need no quotes; Compose and Spring read them as they are |

## Production differences

- `.env` comes from the release pipeline, per environment; staging and prod set
  `SNIGF_PROFILE` accordingly. The guard refuses demo mode and placeholder passwords in prod.
- Secrets come from the hosting institution's secret store or OpenBao, never from
  `dev-setup.sh`.
