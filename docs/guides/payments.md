# Simulated payment and reconciliation

*Copyright (c) 2026 FLYT. Author: Pythagore.*

> **DEMONSTRATION. NO REAL MONEY MOVES.** The only payment provider is a simulator running in
> Compose. No bank, card or mobile-money service is called in any environment. The DEMO fee is a
> "DEMO platform service charge — not an official administrative fee".

## What and why

An applicant pays the DEMO fee of their submitted application, and the intake officer accepts
the case as complete only once it is paid.

- **The provider** is a separate Compose service, `payment-sim`. It answers each charge later,
  with a callback signed with a key it never holds. The callback comes back through the same
  proxy and mutual-TLS path a real provider would use.
- **The platform** reconciles each report against what was requested, by reference **and**
  amount, in one database function under a lock. Anything that does not match stays an
  exception for the finance officer, never a payment.

Decision: [ADR-037](../adr/037-simulated-payment-and-reconciliation.md) (MVP-08, task E-17).

| Outcome | How to cause it (DEMO scenario) | Result |
| --- | --- | --- |
| Paid and matched | `SUCCEED` | request `PAID`; acceptance possible |
| Failed | `FAIL` | request `FAILED`; the applicant may pay again (new reference) |
| Pending, then expired | `NO_RESPONSE` | request expires after `SNIGF_PAYMENT_PENDING_TTL` (24 h) |
| Duplicate callback | `DUPLICATE` | the second copy changes nothing; audited `PAYMENT_CALLBACK_DUPLICATE` |
| Unmatched reference | `UNKNOWN_REFERENCE` | exception `UNMATCHED_REFERENCE`; nothing paid |
| Amount mismatch | `WRONG_AMOUNT` | exception `AMOUNT_MISMATCH`; the request stays unpaid |

## Where

| File | Role |
| --- | --- |
| `backend/src/main/resources/db/migration/V18__payments.sql` | policy, fees, requests, provider reports, resolutions, and the functions that decide them |
| `backend/src/main/resources/payment-fees.json` | the DEMO fee catalogue (`SNIGF_PAYMENT_FEES`) |
| `payment/domain/` | `PaymentProvider` (the replaceable port), `ProviderReport`, `PaymentRequest`, `Fee`, `PaymentReference`, `Scenario`, `Standing`, `Reconciliation`, `PaymentRepository` |
| `payment/application/PaymentService.java` | the applicant's payment: ownership, `Idempotency-Key`, the request committed before the provider is called |
| `payment/application/CallbackService.java` | authenticates through the provider, lets the database decide, audits each outcome |
| `payment/application/PaymentExpiry.java`, `infrastructure/schedule/PaymentExpiryJob.java` | marks and audits expired requests (`SNIGF_PAYMENT_EXPIRY_CRON`) |
| `payment/application/ReconciliationService.java` | the finance officer's lists and resolutions |
| `payment/application/FeeCatalogue.java`, `infrastructure/startup/FeeSync.java` | applies the fee catalogue and the gate at start-up |
| `payment/application/PaymentLookupService.java` | implements `application.PaymentLookup` for the completeness review |
| `payment/infrastructure/provider/` | `DemoSimulatorProvider` (callback authentication, DEMO protocol v1), `HttpsSimulatorChannel` (mutual TLS to `payment-sim`), `InProcessSimulatorChannel` (test profile only), `PaymentProviderConfiguration` |
| `payment/infrastructure/web/` | `PaymentController` (applicant), `CallbackController` (provider), `ReconciliationController` (finance), `PaymentExceptionHandler` |
| `application/application/CaseService.java` | `PAYMENT_REQUIRED` before acceptance |
| `infra/payment-sim/simulator.mjs`, `Dockerfile` | the DEMO simulator |
| `infra/proxy/Caddyfile` | the provider listener on `SNIGF_PROVIDER_CALLBACK_PORT`; the callback path closed on the browser listener |
| `infra/openbao/payment-sim-policy.hcl.template`, `bao-init.sh` | the simulator's HMAC-only policy and certificate login |
| `infra/scripts/dev-pki.sh` | `web_provider_tls`, `payment_sim_tls`, `payment_sim_client`, `app_provider_client` |
| `frontend/src/features/payments/` | `PaymentPanel` (applicant), `FinancePage` (`#/payments`) |
| `backend/src/test/java/org/snigf/mvp/payment/PaymentIT.java`, `backend/src/test/sql/t/20_payments.sql` | the six outcomes, boundaries, concurrency |

| Endpoint | Permission | Answer |
| --- | --- | --- |
| `GET /api/v1/applications/{id}/payment` | `payment.pay#own` | the fee, the gate, the standing (`NOT_REQUIRED`, `DUE`, `PENDING`, `PAID`), the requests; `demo: true` |
| `POST /api/v1/applications/{id}/payments` + `Idempotency-Key`, `{scenario}` | `payment.pay#own` | the same view, after the charge was started |
| `POST /api/v1/payments/callbacks/demo-simulator` | public: provider listener, timestamp, HMAC | `200 {"received": true}`, or `401` with the reason |
| `GET /api/v1/payments`, `/payments/summary`, `/payments/exceptions` | `payment.reconcile` | requests, counts, exceptions |
| `POST /api/v1/payments/exceptions/{id}/resolution` + `Idempotency-Key` | `payment.reconcile` | `201` |

## How it works

1. **Start-up.** `FeeSync` applies `payment-fees.json`: 25,000 XAF for `DEMO_REGISTRATION`, both
   labels saying DEMO. A changed fee closes the version in force and opens a new one from now.
   It also writes the gate (`SNIGF_PAYMENT_REQUIRED_BEFORE_ACCEPTANCE`) to `payment_policy`.
2. **The applicant pays.** On their submitted application, the applicant chooses a DEMO scenario
   and pays.
   1. In the first transaction, `PaymentService` checks the key and the ownership, then opens a
      request `DEMO-PAY-{yyyy}-{seq8}-{check}`. The database copies the amount from the fee in
      force at submission and sets `expires_at`. The request, its audit record
      (`PAYMENT_REQUESTED`) and the idempotency record commit together.
   2. Outside any transaction, `DemoSimulatorProvider` asks `payment-sim` for the charge over
      mutual TLS and receives a transaction id, which is then stored.
   3. If the simulator cannot be reached, the answer is 503 `PROVIDER_UNAVAILABLE`. The request
      waits without a charge, and paying again starts the same one.
3. **The simulator answers.** After `SNIGF_PAYMENT_SIM_CALLBACK_DELAY_MS`, it logs in to OpenBao
   with its certificate. It has the HMAC computed over `<timestamp>.<body>` with the payment
   callback key, and posts the callback to `https://web:47090/...` with its client certificate.
4. **The proxy** requires and verifies that certificate. It writes its subject in
   `X-Snigf-Provider-Client` and forwards the callback to the app over its own mutual TLS.
5. **The app** (`CallbackService`, under the service identity `payment-callback`) checks, in
   order:
   1. that the subject names an allowed provider;
   2. that both headers are present;
   3. that the timestamp is within 5 minutes;
   4. that the HMAC matches, recomputed in OpenBao.

   Only then does it read the body: the DEMO protocol, unknown fields refused.
6. **The database** decides the report in `payment_callback_apply()`. It locks the request, and
   returns at once for a transaction id already recorded. Otherwise:
   - it expires a pending request past its `expires_at`;
   - it decides `MATCHED`, `FAILED`, `AMOUNT_MISMATCH`, `UNMATCHED_REFERENCE`, `AFTER_EXPIRY`,
     `ALREADY_SETTLED` or `NO_EFFECT`;
   - it records the report;
   - it updates the request only for `MATCHED` and `FAILED`.

   The app audits each step, and answers 200 to every authenticated callback.
7. **The gate.** `CaseService.accept` asks `PaymentLookup` and refuses with 409
   `PAYMENT_REQUIRED` while nothing is paid. The trigger `completeness_payment_guard` refuses the
   `COMPLETE` record too.
8. **The finance officer** (`#/payments`) reads the counts and the exceptions. They resolve each
   with `REFUND_SIMULATED` or `NO_ACTION` and a note. A resolution closes the exception, never
   pays a request.

## How to do it again

To add a DEMO fee to another application type:

1. Add an entry to `payment-fees.json`: `typeCode`, `amount` (XAF, whole units), `currency`
   `XAF`, and `labelFr`/`labelEn` saying DÉMO and DEMO.
2. Restart the app. `PAYMENT_FEE_CHANGED` is audited.

Applications submitted earlier keep the fee in force then: none, if there was none.

To change the expiry, the gate or the skew, edit `.env` (`SNIGF_PAYMENT_*`) and restart the app.

**A real provider later** (only once an ADR approves it):

1. Implement `PaymentProvider` in `payment/infrastructure/provider/`, with that provider's own
   charge call and callback authentication.
2. Add its `Kind`.
3. Write a migration widening the `provider` constraints of `payment_policy`, `payment_request`
   and `payment_record`.
4. Add a callback endpoint for it, served by the same provider listener.

Reconciliation, the gate, the audit and the screens stay as they are. Until then,
`SNIGF_PAYMENT_PROVIDER` accepts only `DEMO_SIMULATOR`, and the database refuses anything else.

## How to verify

```bash
docker compose up -d --build --wait                       # payment-sim is part of the stack
docker compose logs payment-sim | head -3                 # "listening on 47091 (DEMO: no real money moves)"
cd backend && ./mvnw verify -Dit.test=PaymentIT           # the six outcomes, boundaries, concurrency
docker compose --profile test run --rm --build dbtest     # pgTAP 20
bash infra/scripts/e2e.sh                                 # the applicant pays before the intake officer accepts
```

In the interface:

1. Sign in as an applicant and submit an application.
2. In the "DEMO payment (simulated)" card, choose "Accept the payment" and pay. A few seconds
   later the card shows "Paid".
3. As `intake.yde@demo.invalid`, the case shows the DEMO fee paid, and the case can be accepted.
4. Try another application with "Report another amount".
5. As `finance.yde@demo.invalid`, `#/payments` lists an "Amount mismatch" to resolve.

The callback path refuses anything unauthenticated. From inside the stack (the probe network),
a callback without a client certificate is refused by the proxy's TLS handshake. Through the
browser listener, the path answers 404.

## Pitfalls we hit

- **Boot's problem details answer first for unreadable bodies.** An unknown `scenario` is a 400
  "Failed to read request", not our `VALIDATION_FAILED`. That is true of every module's
  endpoints today.
- **Nested quoting in pgTAP.** A record id formatted with `%L` inside a string that is itself
  quoted breaks the SQL. Double the quotes (`''%s''`) instead.
- **Moving time in tests.** The request guard refuses any change to `expires_at`, as it should.
  Tests that need a past expiry set `session_replication_role = replica` as the superuser, which
  disables triggers, and keep the check constraint satisfied (move `created_at` too).
- **A transaction around the provider call.** Calling the provider inside the transaction that
  opens the request would let a callback arrive before the request exists, or a rollback forget
  a charge the provider took. So the request commits first, and the charge id is stored after.

## Production differences

- **There is no production payment.** A real provider needs its own ADR, an approved
  adapter and a migration. Its signing scheme would be its own, and its callback listener would
  be published through the edge with that provider's certificate pinned.
- **The simulator is never deployed outside demonstration environments.**
- **Exceptions are resolved by people.** A real refund would be a separate, audited operation
  with the provider; here, "refund (simulated)" only records the decision.
