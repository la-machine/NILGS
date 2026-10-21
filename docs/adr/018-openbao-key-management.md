# ADR-018: Key management in OpenBao; the application never holds key material

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted, implemented | 2026-10-20 | Pythagore (FLYT) |

## Context

The system signs audit checkpoints and decision records and encrypts personal data,
MFA secrets and documents. Keys on the application host can be copied by anyone who
compromises that host.

## Decision

- OpenBao (open-source fork of HashiCorp Vault) runs in the stack in every environment,
  including development, so development exercises the same code paths as production.
  Only automated tests use in-memory keys, and `EnvironmentGuard` refuses them elsewhere.
- The transit engine performs signing, data-key generation and unwrapping, and HMAC;
  long-term keys never leave OpenBao.
- **Authentication by certificate**, not AppRole: the application logs in to
  `auth/cert` with its TLS client certificate (`CN=SNIGF_BAO_APP_CLIENT_CN`, issued by
  the SNIGF CA). Proof of possession of the private key replaces a bearer secret, so no
  password, secret ID or token is stored anywhere. The listener itself requires mutual
  TLS 1.3, so clients without a SNIGF certificate cannot even complete the handshake.
- One key per purpose, versioned for rotation, none exportable, deletable or backed up
  in plaintext:

  | Purpose | Type | Automatic rotation |
  | --- | --- | --- |
  | Audit checkpoint signing | Ed25519 | `SNIGF_KEY_ROTATE_SIGNING` |
  | Server-side record signing | Ed25519 | `SNIGF_KEY_ROTATE_SIGNING` |
  | Data-key wrapping (envelope encryption) | AES-256-GCM, derived per context | `SNIGF_KEY_ROTATE_DATA_ENCRYPTION` |
  | Payment-callback HMAC | HMAC-SHA-256 | `SNIGF_KEY_ROTATE_PAYMENT_CALLBACK` |
  | Password pepper | HMAC-SHA-256 | never automatic |
  | Blind index for encrypted lookups | HMAC-SHA-256 | never automatic |

- The application's policy grants only: sign, read public keys, generate and unwrap data
  keys, HMAC-SHA-256, and look up its own token
  (`infra/openbao/app-policy.hcl.template`). Signatures are verified locally with the
  public keys; MACs by recomputing with the recorded key version.
- **Envelope encryption**: a fresh 256-bit data key per value, wrapped under a key
  derived from the caller's context (for example the record identifier), which is also
  the GCM associated data. A ciphertext moved to another record does not decrypt.
- The application uses one `KeyService` interface (`org.snigf.mvp.keys`); key names,
  mount paths, timeouts and token lifetimes are `.env` settings. At startup it checks
  every key's type and custody settings and round-trips every operation; if any check
  fails, it refuses to start. Readiness includes key custody.
- **Seal and root token**: Shamir seal with `SNIGF_BAO_UNSEAL_SHARES` shares and a
  threshold of `SNIGF_BAO_UNSEAL_THRESHOLD`. No root or administrator token persists:
  provisioning (`bao-init`) generates a temporary root token from a quorum of shares and
  revokes it before exiting. In development the shares are files on the `bao-keys`
  volume, standing in for the custodians.
- For the pilot, OpenBao's seal is backed by an HSM or an external KMS chosen with the
  hosting institution, and unseal shares are held by named custodians.

## Alternatives considered

- **Keys as files read by the app**: simple, but the app host holds the keys.
- **AppRole authentication**: a role ID and secret ID would be one more bearer secret to
  distribute and rotate; certificate login reuses the PKI of ADR-017.
- **Institution HSM directly (PKCS#11)**: strongest custody, but nothing to develop and test
  against until hosting is known; OpenBao can use it later as its seal.
- **Verifying signatures and MACs inside OpenBao**: one more network call per check and
  wider permissions; Ed25519 verification needs only the public key.

## Consequences

- One more service to operate, back up and unseal; its runbook is a pilot requirement
  (`docs/guides/key-custody-openbao.md` describes the development procedure).
- OpenBao 2.x has no `mlock`: hosts must run without swap or with encrypted swap.
- Rotating the pepper or the blind index requires re-hashing or re-indexing; both are
  manual operations.
- Checkpoint signing (ADR-007) uses the OpenBao key, which separates key custody from
  database administration (P-04).
- Delivered in Step 2 of the security foundation.
