# ADR-037: Simulated payment: DEMO fee, signed provider callbacks through the proxy, reconciliation in the database, a replaceable provider port

*Copyright (c) 2026 FLYT. Author: Pythagore.*

| Status | Date | Deciders |
| --- | --- | --- |
| Accepted | 2026-10-27 | Project owner, Pythagore (FLYT) |

## Context

MVP-08 (task E-17) asks for a payment that is **simulated end to end**:

- a DEMO fee schedule;
- a payment request per application, with a DEMO reference;
- a simulated provider that reports outcomes through signed callbacks;
- reconciliation of what the provider reports against what was requested;
- a finance officer who handles the exceptions.

**No real money moves, and no real payment API is called in any environment.**

The groundwork already existed:

- the permissions `payment.pay` (APPLICANT, OWN) and `payment.reconcile` (FINANCE_OFFICER, ANY) (ADR-025);
- the HMAC key `PAYMENT_CALLBACK`, held in OpenBao, non-exportable and rotated (ADR-018);
- the empty `payment` module;
- the flag ADR-034 deferred to E-17: payment confirmed before completeness is accepted.

The project owner set these decisions (27 Oct 2026):

| # | Decision |
| --- | --- |
| 1 | **Payment gate on by default.** An unpaid application cannot have its completeness accepted. The behaviour is set by an explicit setting. |
| 2 | **DEMO fee of 25,000 XAF** for `DEMO_REGISTRATION`, from a fee catalogue. Labelled "DEMO platform service charge — not an official administrative fee". |
| 3 | **Pending payments expire after 24 hours**, configurable. A late callback can never turn an expired request into a paid one. |
| 4 | **A separate Compose simulator.** It sends signed callbacks through the same proxy and mutual-TLS path a future provider would use. |

They also required:

- signatures checked, and unsigned or badly signed callbacks rejected;
- provider transaction ids processed once;
- matching by reference **and** amount, with mismatches and unknown references kept as exceptions;
- an `Idempotency-Key` on payment operations, and the applicant's ownership enforced;
- an audit record of every step;
- signing keys in OpenBao, never in `.env`;
- a provider port that a future, separately approved provider can implement. That provider stays disabled until then.

## Decision

### The flow

```
applicant ──POST /applications/{id}/payments (Idempotency-Key)──▶ app ──(1) payment_request PENDING, commit
                                                                     └─(2) mutual TLS ──▶ payment-sim  POST /v1/charges
payment-sim ──(3) HMAC from OpenBao (its own login, hmac only)
            ──(4) mutual TLS ──▶ web :SNIGF_PROVIDER_CALLBACK_PORT ──mutual TLS──▶ app POST /api/v1/payments/callbacks/demo-simulator
app ──(5) signature + freshness + provider identity checked ──▶ payment_callback_apply() in the database
intake officer ──accept──▶ refused (409 PAYMENT_REQUIRED) unless a request is PAID: service and database
```

### The DEMO fee catalogue (`fee_schedule`)

- **Source.** `SNIGF_PAYMENT_FEES` (`classpath:payment-fees.json`) holds one fee per
  application type: amount, currency (XAF only) and labels. It is applied at start-up, like the
  type catalogue (ADR-032):
  - a fee that changed closes the version in force and opens a new one, from now;
  - a fee no longer configured is closed;
  - each change is audited (`PAYMENT_FEE_CHANGED`).
- **Database rules.** Versions never overlap: a trigger takes a lock per type and refuses an
  overlap, so no `btree_gist` extension is needed. A version is never edited, except to close it
  once. Every row is `demo = true` by constraint.
- **The amount owed** is the one in force when the application was **submitted**. A fee raised
  later never applies to cases already filed.
- **Labels.** "DEMO platform service charge — not an official administrative fee" /
  « Frais de service de la plateforme DÉMO — pas un droit administratif officiel ».

### Payment requests (`payment_request`)

- **Reference.** `DEMO-PAY-{yyyy}-{seq8}-{check}`: a national counter
  (`next_reference_number('PAY', 'ALL', year)`) and the check character of every DEMO reference.
- **Amount.** Copied from the fee by the database trigger, never from the caller.
- **Expiry.** `expires_at` is the creation time plus `SNIGF_PAYMENT_PENDING_TTL` (`PT24H`).
  The database bounds it to between 1 minute and 7 days.
- **States.** `PENDING` → `PAID` | `FAILED` | `EXPIRED`. Those three are final.
  - `PAID` is refused once `expires_at` has passed;
  - `EXPIRED` is refused before it.
  - The status changes only inside the database's payment functions: `snigf_app` holds no
    `UPDATE` on it.
- **One payment at a time.** A partial unique index allows only one `PENDING` or `PAID` request
  per application. After `FAILED` or `EXPIRED`, the applicant may pay again, which opens a new
  request.
- **Who opens a request.** Only the applicant of a submitted application still in progress, with
  a fee in force at its submission (trigger). Row-level security shows a request to whoever sees
  the application.

### Opening a payment (applicant)

`POST /api/v1/applications/{id}/payments`:

- **Who.** `@access.canOwn(authentication, 'payment.pay')`, then the service's ownership check
  (404 otherwise), then row-level security and the trigger.
- **`Idempotency-Key`** is required (`IDEMPOTENCY_KEY_REQUIRED`). A retry returns the same
  payment's current state. The same key with another body is refused.
- **`scenario`**: what the DEMO provider will do. One of:
  - `SUCCEED`, `FAIL`, `NO_RESPONSE` (the payment then expires);
  - `DUPLICATE` (the success sent twice), `WRONG_AMOUNT`, `UNKNOWN_REFERENCE`.

  The scenario exists only because the provider is a simulator. It is stored with the request and
  audited.
- **Two transactions, never a call inside one:**
  1. the request is committed;
  2. the provider is called;
  3. the provider's charge id is stored.

  If the provider cannot be reached, the request stays `PENDING` without a charge id, and the
  answer is 503 `PROVIDER_UNAVAILABLE`. A later `POST` on the application starts that same request
  again rather than opening a second one.

### The simulator (`payment-sim`)

- **What it is.** A Compose service: Node.js with no dependencies, read-only and non-root, all
  capabilities dropped. It holds no money and makes no network call outside the stack.
- **Charges.** It accepts `POST /v1/charges` over mutual TLS 1.3 only from the application's
  provider-client certificate (`SNIGF_PAYMENT_APP_CLIENT_CN`). It answers with a transaction id
  (`SIMTX-…`), then sends the scenario's callbacks after `SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS`.
  - A failed delivery is retried three times. Duplicates are therefore normal and harmless.
- **Signing.** It never holds the key. It logs in to OpenBao with its own client certificate and
  role (`SNIGF_BAO_PAYMENT_SIM_ROLE`), whose policy allows only
  `transit/hmac/<payment key>/sha2-256`. The application recomputes the HMAC in OpenBao with the
  version named in the header and compares in constant time (`KeyService.verifyMac`).
- **The message signed** is `<timestamp>.<raw body>`. The headers are:
  - `X-Snigf-Timestamp` (Unix seconds);
  - `X-Snigf-Signature: v<key version>=<base64>`.
- **Callback body** (DEMO protocol v1): `provider`, `transactionId`, `reference`, `amount`,
  `currency`, `status` (`SUCCEEDED` | `FAILED`), `occurredAt`, `demo: true`.

### The callback path (zero trust)

- **Networks.** Three new internal networks give the simulator exactly three peers:
  - `provider-out`: app → simulator;
  - `provider-in`: simulator → proxy;
  - `provider-key`: simulator → OpenBao.

  The simulator never reaches the database or OpenBao's other keys. It can open a connection to
  the application's port on `provider-out`, where the application calls it, but the application
  refuses its certificate (`ClientCertificateFilter` admits the proxy only, 403
  `TRANSPORT_CLIENT_REJECTED`). Its callbacks therefore have one way in: the proxy. The
  security probe checks both.
- **A second listener on the proxy,** `SNIGF_PROVIDER_CALLBACK_PORT` (47090), not published on
  the host:
  - TLS 1.3 with its own certificate (`web_provider_tls`, name `web`);
  - a client certificate from the SNIGF CA is **required and verified**;
  - it serves only `/api/v1/payments/callbacks/*`, with a 16 KB body limit, and answers 404 to
    everything else;
  - it writes the verified certificate subject in `SNIGF_PROXY_PROVIDER_CLIENT_HEADER`.

  On the browser listener, that header is removed and the callback path answers 404.
- **What the application checks, in order.** Any failure answers 401 or 403, writes nothing in the
  database, and is audited `PAYMENT_CALLBACK_REJECTED` with the reason (never the body):
  1. the provider identity header names an allowed provider (`SNIGF_PAYMENT_PROVIDER_CLIENT_CNS`);
     it is believed only on the proxy's own certificate;
  2. both signature headers are present and well formed;
  3. the timestamp is within `SNIGF_PAYMENT_CALLBACK_MAX_SKEW` (`PT5M`);
  4. the HMAC matches.

  Only then is the body parsed. A malformed body is answered 400 `CALLBACK_MALFORMED` and audited.

### Reconciliation in the database (`payment_callback_apply`)

One `SECURITY DEFINER` function decides each authenticated report. It locks the request
named by the reference (`FOR UPDATE`), and records the report in `payment_record`, which is
append-only, with `provider_transaction_id` unique.

- **Idempotency.** The report is inserted with `ON CONFLICT DO NOTHING`. A transaction id already
  recorded comes back as `duplicate`, with the first outcome, and changes nothing; it is audited
  `PAYMENT_CALLBACK_DUPLICATE`.
- **Concurrency.** Concurrent copies of one callback serialise on the request lock, or on the
  unique index when no request matches: exactly one wins.
- **Outcomes:**

| Report | Request found | Outcome | Effect | Exception |
| --- | --- | --- | --- | --- |
| `SUCCEEDED` | `PENDING`, not expired, same amount and currency | `MATCHED` | request `PAID` | no |
| `FAILED` | `PENDING`, not expired, same amount and currency | `FAILED` | request `FAILED` | no |
| `SUCCEEDED` | `PENDING`, not expired, other amount or currency | `AMOUNT_MISMATCH` | request stays `PENDING` | **yes** |
| `SUCCEEDED` | none | `UNMATCHED_REFERENCE` | none | **yes** |
| `SUCCEEDED` | `PENDING` past `expires_at`, or `EXPIRED` | `AFTER_EXPIRY` | request `EXPIRED` (at once if still pending) | **yes** |
| `SUCCEEDED` | `PAID` or `FAILED` | `ALREADY_SETTLED` | none | **yes** |
| `FAILED` | anything else | `NO_EFFECT` | none | no |

- **The rule.** Money reported taken without a pending request of the same amount is an exception,
  never a payment. A failure that changes nothing is recorded and has no other effect.
- **Expiry is decided by `expires_at`, not by the sweep.** A callback that arrives after it finds
  the request expired, whether or not the scheduled job (`SNIGF_PAYMENT_EXPIRY_CRON`) has run.
  The job (`payment_expire_due()`) only makes the state visible and audits `PAYMENT_EXPIRED`.

### The finance officer (`payment.reconcile`)

The finance officer reads through `SECURITY DEFINER` functions gated on `payment.reconcile`.
They need no `application.read`, and see references and amounts, never a form or a document.

| Endpoint | What it does |
| --- | --- |
| `GET /api/v1/payments` | requests by status, with the matching report |
| `GET /api/v1/payments/exceptions` | exceptions, open or resolved |
| `POST /api/v1/payments/exceptions/{id}/resolution` | resolves an exception |

- **A resolution** is `REFUND_SIMULATED` or `NO_ACTION`, with a note. It is append-only and audited
  `PAYMENT_EXCEPTION_RESOLVED`, and needs an `Idempotency-Key`.
- **It closes the exception; it never pays the request.** Nobody resolves an exception on their
  own application (trigger).

### The gate (decision 1)

- **The setting.** `SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE` (default `true`) is written to
  `payment_policy` at start-up. A change is audited (`PAYMENT_POLICY_CHANGED`).
- **When it blocks.** A fee was in force at submission and no request is `PAID`.
- **Where it is checked.**
  - `CaseService.accept` refuses with 409 `PAYMENT_REQUIRED`. The application module asks
    through its own port, `PaymentLookup`, which the payment module implements, so applications
    never depend on payments.
  - The trigger `completeness_payment_guard` refuses the `COMPLETE` record too
    (`object_not_in_prerequisite_state`).
- **What people see.** Staff see the payment status on the case. The applicant sees it on their
  application, with the DEMO label everywhere.

### A replaceable provider port (the final point)

- **The port.** `payment.domain.PaymentProvider` is what application processing uses: start a
  charge, authenticate a callback, read a report.
- **The adapter.** `DemoSimulatorProvider` is the only one. Its HTTPS channel is replaced in tests
  by an in-process channel, which is refused outside the test profile.
- **The settings.** `SNIGF_PAYMENT_PROVIDER` accepts only `DEMO_SIMULATOR`. The database's own
  constraints (`provider = 'DEMO_SIMULATOR'` on `payment_policy`, `payment_request` and
  `payment_record`) refuse anything else.
- **A future provider** needs four things: an adapter, an enum value, a migration widening those
  constraints, and a new ADR approving it. Until then nothing can switch it on.

## Alternatives rejected

- **The simulator holding the HMAC key in `.env` or a file.** Rejected: keys stay in OpenBao
  (ADR-018).
- **Callbacks straight to the application on an internal network.** Rejected: the path would differ
  from a real provider's, and the application would need a second trusted client identity.
- **Opening the request at submission.** Rejected: the 24-hour clock would run before the applicant
  chose to pay, and abandoned requests would expire in bulk. The request opens when the applicant
  pays.
- **Letting the finance officer mark a mismatch as paid.** Rejected by the owner: exceptions stay
  exceptions.
- **The status changed by the service with a plain `UPDATE`.** Rejected: one database function
  decides under a lock, so concurrent callbacks and expiry cannot race.

## Consequences

- **New:**
  - migration V18 and pgTAP test `20`;
  - the payment module: domain, service, provider port and simulator adapter, controllers, expiry job;
  - `PaymentLookup` in the application module, and `PAYMENT_REQUIRED` on acceptance;
  - eleven audit actions;
  - the `payment-sim` service, the proxy's provider listener, three networks;
  - four certificates (`web_provider_tls`, `payment_sim_tls`, `payment_sim_client`, `app_provider_client`);
  - an OpenBao role and policy for the simulator;
  - the frontend `features/payments` (the applicant's panel and the finance page);
  - the guide `docs/guides/payments.md`.
- **Existing flows** now pay before acceptance: the integration tests' `accepted()` helper and
  the end-to-end journey.
- **Running the backend from the IDE:** the simulator's callbacks cannot reach a backend outside
  Compose, so payments stay pending there. Use the full stack.
