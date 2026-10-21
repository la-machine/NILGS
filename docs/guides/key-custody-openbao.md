# Key custody with OpenBao

*Copyright (c) 2026 FLYT. Author: Pythagore.*

## What and why

Land-title records must stay verifiable and confidential even if the application server
is compromised. So the application **never holds a long-term key**:

- **Signing, data-key wrapping and HMAC** happen inside OpenBao's transit engine.
- **Authentication** uses the application's TLS client certificate, not a stored password
  or token.
- **Permissions** come from a policy that allows using keys, never exporting, rotating or
  deleting them.
- **Startup self-test:** the application checks all of this and refuses to start if
  anything is wrong.

Decision: [ADR-018](../adr/018-openbao-key-management.md).

## Where

| File | Role |
| --- | --- |
| `infra/openbao/Dockerfile` | Two images from the pinned upstream: `server` (configuration + start script) and `init` (adds curl and jq for provisioning) |
| `infra/openbao/openbao.hcl.template` | Server configuration with `@PLACEHOLDERS@`: Raft storage, mutual-TLS 1.3 listener, audit to stdout and file |
| `infra/openbao/start-openbao.sh` | Renders the template from `.env` values into a tmpfs, refuses unrendered placeholders, starts the server |
| `infra/openbao/bao-init.sh` | Provisioning job: initialise, unseal, provision when inputs change, revoke the root token |
| `infra/openbao/app-policy.hcl.template` | The application's policy (paths it may use) |
| `compose.yml` | Services `openbao` and `bao-init`, network `key-net`, volumes `bao-data`, `bao-audit`, `bao-keys` |
| `compose.dev.yml` | Publishes OpenBao on `127.0.0.1:47820` for a backend started from the IDE |
| `.env.example` | `SNIGF_BAO_*` (ports, unseal shares, mounts, role, token lifetimes, timeouts) and `SNIGF_KEY_*` (key names, rotation) |
| `backend/.../keys/` | Public API: `KeyService`, `SigningKey`, `EncryptionKey`, `MacKey`, `Signature`, `Mac`, `SealedData`, `SigningPublicKey` |
| `backend/.../keys/internal/` | `OpenBaoClient` (certificate login, token renewal), `OpenBaoKeyService`, `EphemeralKeyService` (tests only), `KeyServiceSelfTest`, `KeysHealthIndicator`, `KeysConfiguration` |
| `backend/src/main/resources/application-key-custody.yaml` | SSL bundle `snigf-openbao` and `snigf.keys.*` from `.env` (profiles dev, staging, prod) |
| `infra/probe/security-probe.sh` | OpenBao section: transport, login and policy checks |

## How it works

### Start-up sequence (`docker compose up`)

1. **`openbao`** starts sealed. Its health check (`bao status`) stays unhealthy until it
   is unsealed.
2. **`bao-init`** runs to completion, with every API call over mutual TLS using the
   `bao_admin_client` certificate:
   1. Waits for the API (`SNIGF_BAO_INIT_TIMEOUT`).
   2. **First run only:** initialises with `SNIGF_BAO_UNSEAL_SHARES` shares and threshold
      `SNIGF_BAO_UNSEAL_THRESHOLD`. It writes one file per share to the `bao-keys`
      volume, standing in for the custodians.
   3. If sealed, submits a threshold of shares.
   4. Computes a fingerprint of its inputs: the script, the policy template, the CA chain
      and every `SNIGF_BAO_*` / `SNIGF_KEY_*` value. If the fingerprint equals the stored
      one, it stops here.
   5. Otherwise it obtains a root token:
      - **first run:** from initialisation;
      - **later runs:** by the generate-root ceremony, a one-time password plus a
        threshold of shares, decoded locally.
   6. With that token it provisions, idempotently:
      - the transit engine;
      - the six keys, created as non-exportable, without plaintext backup, then set to
        non-deletable with their rotation period;
      - the policy;
      - the certificate login role.
   7. **Revokes the root token**, checks it no longer works, and stores the fingerprint.
3. **`app`** starts once `bao-init` succeeded and `openbao` is healthy:
   1. It logs in to `auth/<SNIGF_BAO_AUTH_MOUNT>/login` with its client certificate, over
      mutual TLS 1.3 with host-name verification. The token lives in memory only and is
      replaced after two thirds of its lifetime, or at once if it is rejected.
   2. `KeyServiceSelfTest` checks every key's type and custody settings, signs and
      verifies, seals and opens, and computes and verifies each MAC; each must also reject
      tampered input. Any failure stops startup before the web server accepts a request.
   3. Readiness (`/actuator/health/readiness`) includes the `keys` contributor (token
      self-lookup) and `db`.

### Operations

| `KeyService` method | Inside OpenBao | In the application |
| --- | --- | --- |
| `sign(SigningKey, msg)` | `transit/sign/<key>` (Ed25519) | — |
| `verify(...)` | — | Ed25519 check with the cached public key of the signature's version |
| `publicKeys(SigningKey)` | `transit/keys/<key>` (public halves only) | for offline verifiers |
| `seal(EncryptionKey, data, context)` | `transit/datakey/plaintext/<key>` with `context` (derived key) | AES-256-GCM with that one-time data key, `context` as associated data; data key erased |
| `open(...)` | `transit/decrypt/<key>` with `context` | AES-256-GCM decryption; data key erased |
| `mac(MacKey, msg)` | `transit/hmac/<key>/sha2-256` | — |
| `verifyMac(...)` | `transit/hmac/...` with the recorded `key_version` | constant-time comparison |

Stored forms are `snigf:v<version>:<base64>` for signatures and MACs. `SealedData` keeps
three parts: the wrapped key (OpenBao's opaque string, which carries the key version),
the nonce and the ciphertext.

## How to do it again

### Use keys in a module

```java
@Service
class DecisionSigner {
    private final KeyService keys;   // inject the interface, never a provider

    DecisionSigner(KeyService keys) { this.keys = keys; }

    String sign(byte[] canonicalRecord) {
        return keys.sign(SigningKey.RECORD, canonicalRecord).encoded();
    }

    boolean isAuthentic(byte[] canonicalRecord, String stored) {
        return keys.verify(SigningKey.RECORD, canonicalRecord, Signature.parse(stored));
    }
}
```

- Sign a **canonical** byte form (fixed field order and encoding); otherwise the same
  record can produce different bytes.
- For `seal`/`open`, pass the record's identifier as `context`. A ciphertext copied to
  another record will not open.
- On `KeyServiceException`, fail the operation. Never fall back to unsigned or plaintext data.

### Add a key

1. Add a constant to `SigningKey`, `EncryptionKey` or `MacKey`.
2. Add its name setting to `.env.example` (`SNIGF_KEY_<NAME>`), to `x-key-names` in
   `compose.yml`, to `SnigfProperties.KeyNames`, to `application-key-custody.yaml` and to
   `application-test.yaml`.
3. Map it in `CustodyKeyService.name(...)` and add it to `OpenBaoKeyService.verifyCustody()`.
   Also add it to the duplicate-name list in `EnvironmentGuard`.
4. Create it in `bao-init.sh` (`key "$SNIGF_KEY_<NAME>" <type> <rotation>`) and grant
   only the paths it needs in `app-policy.hcl.template`.
5. Run `bash infra/scripts/dev-setup.sh` and `docker compose up -d --build --wait`. The
   changed fingerprint makes `bao-init` provision again. The self-test then covers the
   key automatically, because it iterates over the enums.
6. Add tests, update this guide and add a `CHANGELOG.md` entry.

### Rotate a key

Automatic rotation is set by `SNIGF_KEY_ROTATE_*`, as hours or `0` for none. Old versions
keep verifying and decrypting. To rotate now, run with a root token obtained by the
generate-root ceremony:

```sh
bao write -f transit/keys/<name>/rotate
```

Then revoke the token. Never rotate the password pepper or the blind index without a
re-hashing or re-indexing plan: existing values would stop matching.

### Reset development custody

```sh
docker compose down -v      # deletes db-data, bao-data, bao-audit and bao-keys together
docker compose up -d --build --wait
```

Never delete `bao-keys` alone. OpenBao would stay sealed for ever, and everything
encrypted under its keys would be lost.

### Read the audit log

Every request and response is audited, with secret values replaced by HMACs:

```sh
docker compose logs openbao | grep '"type":"response"'
docker compose exec openbao sh -c 'tail -n 5 /openbao/audit/audit.log'
```

## How to verify

```sh
docker compose logs bao-init                     # "provisioning up to date" or "... root token revoked"
docker compose logs app | grep "self-test"       # "Key custody self-test passed: 2 signing, 1 encryption, 3 MAC keys"
docker compose --profile test run --rm security-probe     # OpenBao section, all PASS
docker compose --profile test run --rm segmentation-probe # "app-net cannot reach OpenBao"
cd backend && ./mvnw verify                      # OpenBaoKeyServiceTest, EphemeralKeyServiceTest, guard tests
```

The probe's OpenBao section covers:

- **Positive controls:** login, and signing.
- **TLS refusals:** TLS 1.2, no certificate, and a certificate from a foreign CA (even
  with the application's name).
- **Login refusal:** another service's certificate from the same CA, refused with "no
  chain matching".
- **Key settings:** keys are not exportable, not deletable, and have no plaintext backup.
- **Policy:** 14 operations outside it return 403 for the application's token.

## Pitfalls we hit

| Symptom | Cause | Fix |
| --- | --- | --- |
| `sh: can't open '/etc/snigf/...': Permission denied` | `COPY --chmod=444` into a new directory created the directory with mode 444 (no search bit) | Create the directory first: `install -d -m 755 /etc/snigf` |
| Log warning "unknown or unsupported field disable_mlock" | OpenBao 2.x removed `mlock` support | Remove the setting. Hosts must run without swap or with encrypted swap |
| `sys/generate-root/attempt` → "unsupported operation" | `disable_unauthed_generate_root_endpoints` is a **listener** option, not a top-level one; top-level, it is ignored with only a warning | Put it inside `listener "tcp" { ... }`. Read the server log for "unknown or unsupported field" after every configuration change |
| `bao operator generate-root -decode` → 403 | In OpenBao 2.x the CLI decodes through an authenticated endpoint, and no token exists yet | Decode locally: the encoded token is base64 (unpadded) of token XOR one-time password (`decode_token` in `bao-init.sh`) |
| The start script refused a correct configuration ("unrendered placeholder") | The placeholder check also matched `@NAME@` in a comment | Ignore comment lines when checking |
| `RestClient.Builder` bean not found | Spring Boot 4 moved the `RestClient` auto-configuration into a separate module | Build the client from Spring Framework's `RestClient.builder()`, with an explicit TLS 1.3 `HttpClient` |

## Production differences

- **Custodians:** each unseal share goes to a named custodian, and the `bao-keys` volume
  does not exist. Unsealing and the generate-root ceremony need a quorum of custodians
  present. The seal is backed by the institution's HSM or KMS (auto-unseal) once hosting
  is chosen.
- **Backups:** take Raft snapshots (`bao operator raft snapshot save`) on a schedule,
  encrypted and stored apart from the database backups; restore drills are part of the
  runbook.
- **Hosts:** no swap (or encrypted swap), core dumps disabled, the audit log shipped to the
  central log store.
- **Certificates:** OpenBao's own TLS and the client certificates come from the production
  PKI ([transport security](transport-security.md#production-differences)).
