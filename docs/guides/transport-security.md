# Transport security and hardening

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Nothing is trusted, not even the internal network. Every connection uses TLS 1.3, and
every internal connection uses **mutual** TLS: both sides prove their identity with a
certificate from the SNIGF private CA. On top of that, each server admits only the
certificate names it expects. Containers run with the least privilege that still works,
and the networks are segmented so a compromised proxy reaches neither the database nor
the keys. Decisions: [ADR-017](../adr/017-tls-on-every-hop.md),
[ADR-020](../adr/020-container-and-network-hardening.md).

| Hop | Server enforces | Client presents |
| --- | --- | --- |
| Browser → proxy (`web`, 47000) | TLS 1.3, HSTS; no plain-HTTP listener | nothing (public) |
| Proxy → app (47080) | mutual TLS 1.3; CN must be `SNIGF_PROXY_CLIENT_CN` | `web_client` certificate |
| App, migrate → db (47432) | TLS 1.3; client certificate whose CN equals the role; SCRAM password | `app_db_client` or `migrator_db_client` + password |
| App → OpenBao (47820) | mutual TLS 1.3; login only for CN `SNIGF_BAO_APP_CLIENT_CN` | `app_bao_client` |

## Where

| File | Role |
| --- | --- |
| `infra/scripts/dev-pki.sh` | Development CA hierarchy and every service certificate; renewal; `--rotate` |
| `infra/secrets/pki/` | CA private keys and working files; **never mounted** into any container |
| `infra/secrets/<name>_crt`, `<name>_key`, `<name>_key_pk8` | Leaf + intermediate chain, PEM key, DER PKCS#8 key (for JDBC) |
| `infra/proxy/Caddyfile` | Browser TLS, security headers, mutual TLS to the app |
| `backend/src/main/resources/application-transport-security.yaml` | Server SSL bundle (TLS 1.3, `client-auth: need`), JDBC TLS settings |
| `backend/.../shared/web/ClientCertificateFilter.java` | Admits only allowed client CNs; refuses with `TRANSPORT_CLIENT_REJECTED` |
| `backend/.../shared/config/EnvironmentGuard.java` | Refuses to start without complete transport security (except `test`) |
| `infra/db/pg_hba.conf`, `infra/db/start-postgres.sh` | Database authentication rules; TLS 1.3 server settings |
| `infra/openbao/openbao.hcl.template` | OpenBao listener: TLS 1.3, client certificate required |
| `compose.yml` | Networks, hardening anchor `x-hardened`, per-service secrets, resource limits |
| `infra/probe/security-probe.sh` | Checks every guarantee against the running stack, each refusal for its reason |

## How it works

### The PKI

```text
SNIGF Development Root CA      P-384, SNIGF_PKI_ROOT_DAYS, key in infra/secrets/pki/
└── SNIGF Development Services CA   P-384, pathlen 0, SNIGF_PKI_INTERMEDIATE_DAYS
    ├── web_tls      server  CN=SNIGF_PUBLIC_HOSTNAME
    ├── app_tls      server  SAN app, localhost, 127.0.0.1
    ├── db_tls       server  SAN db, localhost, 127.0.0.1
    ├── bao_tls      server  SAN openbao, localhost, 127.0.0.1
    ├── web_client           client  CN=SNIGF_PROXY_CLIENT_CN
    ├── app_db_client        client  CN=snigf_app
    ├── migrator_db_client   client  CN=snigf_migrator
    ├── app_bao_client       client  CN=SNIGF_BAO_APP_CLIENT_CN
    ├── bao_admin_client     client  CN=SNIGF_BAO_ADMIN_CLIENT_CN
    └── bao_health_client    client  CN=SNIGF_BAO_HEALTH_CLIENT_CN
```

- Server certificates carry `extendedKeyUsage=serverAuth`; client certificates carry
  `clientAuth`. A certificate cannot serve both roles.
- Leaves are P-256, valid `SNIGF_PKI_LEAF_DAYS`. Each run of `dev-pki.sh` re-issues a leaf
  when it is missing, no longer verifies, or expires within `SNIGF_PKI_RENEW_BEFORE_DAYS`.
- `pki_ca_chain_crt` (intermediate + root) is the trust anchor every service receives.
  `pki_root_ca_crt` is for your browser and for `curl --cacert`.

### The hops

1. **Browser → proxy.** Caddy terminates TLS with `protocols tls1.3`. It sends HSTS, CSP
   and the other security headers. `auto_https disable_redirects` means no plain-HTTP
   listener exists at all.
2. **Proxy → app.** The `/api/*` path goes to `reverse_proxy https://app:<port>` with
   `tls_client_auth` (the proxy's client certificate) and `tls_trust_pool` (the SNIGF
   CA). Spring Boot requires the client certificate (`server.ssl.client-auth: need`).
   `ClientCertificateFilter` then admits only the CNs listed in
   `snigf.security.transport.allowed-client-common-names`, so another service's
   certificate from the same CA is refused. The proxy also overwrites the header
   `SNIGF_PROXY_CLIENT_ADDRESS_HEADER` with the browser's address; the app believes it
   only on a request admitted on the proxy's certificate (`ClientAddress`, used by audit).
3. **App → db.** JDBC uses `sslmode=verify-full`, `sslrootcert`, `sslcert` and `sslkey`
   (DER PKCS#8). `pg_hba.conf` has only `hostssl` lines with
   `scram-sha-256 clientcert=verify-full`: TLS, a certificate whose CN equals the role,
   *and* the password are all required. The superuser logs in only through the local
   socket (`peer`).
4. **App → OpenBao.** See [key custody](key-custody-openbao.md).

### Hardening

- Every service inherits `x-hardened`:
  - `read_only: true`;
  - `cap_drop: [ALL]`;
  - `no-new-privileges`;
  - `restart: unless-stopped`.

  Writable paths are `tmpfs` mounts or named volumes. Non-root users are fixed UIDs:
  web 10002, migrate 10003, app 10001, probe 10009, openbao 100.
- Networks:
  - `edge`: the published proxy port only.
  - `app-net`: web ↔ app.
  - `data-net`: app, migrate ↔ db.
  - `key-net`: app, bao-init ↔ openbao.

  All but `edge` are `internal: true`, so they have no internet. Only the proxy's HTTPS
  port is published, and only on `127.0.0.1`.
- Memory, CPU and process limits come from `.env` (`SNIGF_*_MEMORY`, `_CPUS`, `_PIDS`).

## How to do it again

### Give a new service TLS

1. Choose a port (see [settings](environment-and-secrets.md#choose-a-port)).
2. Issue its certificate. In `dev-pki.sh` add
   `issue <name>_tls server <service> "DNS:<service>,DNS:localhost,IP:127.0.0.1"`.
   If it calls another service, also add
   `issue <name>_<target>_client client <cn>`.
3. Declare the new files under `secrets:` in `compose.yml` and list them under the
   service, together with `pki_ca_chain_crt`.
4. Configure the server for TLS 1.3 only, with the client certificate required and
   verified against `pki_ca_chain_crt`. Then restrict the accepted names at the
   application layer (CN allow-list, role mapping or login role).
5. Configure every client with the CA, its client certificate and host-name verification.
6. Put the service on the narrowest network, never on `edge` unless it is public.
7. Add probe checks for each item below. Each refusal must assert its reason (the error
   text), next to a positive control that succeeds:
   - the positive control itself;
   - TLS 1.2 refused;
   - no certificate refused;
   - a certificate from a foreign CA refused;
   - another service's certificate from the same CA refused.
   - In the segmentation probe, add a check that networks which must not reach the
     service cannot.
8. Run `bash infra/scripts/dev-setup.sh`, then `docker compose up -d --build --wait` and
   both probes.

### Rotate certificates

```sh
bash infra/scripts/dev-pki.sh --rotate    # every leaf, same CA
docker compose up -d --force-recreate     # services reload their files
```

Deleting `infra/secrets/pki/` creates a new CA. Every leaf is then re-issued, and you must
trust the new root again in your browser.

### Trust the development CA in your browser

On Windows, for your user only:

```text
certutil -user -addstore Root infra\secrets\pki_root_ca_crt
```

Remove it with `certutil -user -delstore Root "SNIGF Development Root CA"`. Never install
it on a machine used for anything else.

## How to verify

```sh
docker compose --profile test run --rm --build security-probe     # expect: N passed, 0 failed
docker compose --profile test run --rm segmentation-probe          # expect: 4 passed, 0 failed
docker inspect snigf-app-1 --format '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.Config.User}}'
```

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| The probe connected to the backend with TLS 1.2 | With an SSL bundle, Spring Boot **ignores** `server.ssl.enabled-protocols` | Set `spring.ssl.bundle.pem.<bundle>.options.enabled-protocols: TLSv1.3`; the guard reads that property |
| `exec /usr/bin/caddy: operation not permitted` | The binary carries the `cap_net_bind_service` file capability; with `cap_drop: ALL` and `no-new-privileges` the kernel refuses to run it | `setcap -r /usr/bin/caddy` in the image (47000 needs no capability) |
| Certificate subjects became `C:/Program Files/Git/O=FLYT/...` | Git Bash rewrites arguments that look like paths | `export MSYS2_ARG_CONV_EXCL="/O="` in `dev-pki.sh` |
| Wrong-role certificate: the client sees only "authentication failed" | PostgreSQL logs the real reason ("CN mismatch") but tells the client nothing | The probe accepts that message, and the positive control proves the certificate is the cause |
| Windows `curl` fails: revocation check | Schannel checks revocation, and the development CA publishes none | Use `--ssl-no-revoke` locally. The pilot PKI must publish a CRL or OCSP |
| Windows `curl` cannot use PEM client certificates | Schannel limitation | Run mutual-TLS checks from the probe container (OpenSSL curl) |
| A check passed for the wrong reason (HTTP 400 from a malformed request) | Status-only assertions | Assert the error text as well as the status |

## Production differences

- The root CA key is held offline by the release manager. Services get short-lived
  certificates, from OpenBao's PKI engine with automatic renewal (planned for the pilot),
  never from `dev-pki.sh`.
- The pilot PKI publishes revocation (CRL or OCSP).
- The public endpoint, on port 443 or another port, is decided with the hosting
  institution (P-11). Its certificate comes from a CA browsers trust.
