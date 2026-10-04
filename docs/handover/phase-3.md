# Phase 3 Handover: Payments, Escrow, Double-Entry Ledger, Audit Trail

> **Status:** ✅ Complete · backend 35 unit + 31 integration tests (real PostgreSQL) · frontend 57 unit tests · full money flow verified in Chromium against the live API
>
> **New backend modules:** `payments`, `ledger`, `escrow`, `audit` (+ `Idempotency` in `shared`) · **Migration:** `V4__ledger_payments_escrow_audit.sql` · **New pages:** `/deals`, `/ledger`

---

## 0. TL;DR

Winning an auction now moves real (test) money through a production-style pipeline:

- **Deposit holds** are card *authorizations*, not charges (Stripe `capture_method=manual`). Losing bidders' holds are voided automatically. The winner's hold is captured into escrow.
- **Escrow** is a state machine. The buyer pays the balance, confirms delivery, and only then is the seller paid, minus a 5% fee.
- **Every dollar** is a balanced, append-only **double-entry** journal entry. The database itself rejects unbalanced or edited entries.
- **Every event** is written to a **SHA-256 hash-chained audit log**. Editing any row, even as a DB admin, is detected.
- **Retries are safe everywhere:**
  - `Idempotency-Key` on money-moving API calls
  - deterministic idempotency keys sent to Stripe
  - signed, de-duplicated webhooks
  - forward-only state machines
- **Two payment gateways behind one interface:** `SIMULATED` (default, no keys, mirrors Stripe's test tokens) and `STRIPE` (test mode, refuses live keys).

| Escrow deal, paid out | Ledger & audit trail |
|---|---|
| ![deal](../screenshots/13-deal-paid-out.png) | ![ledger](../screenshots/14-ledger.png) |

---

## 1. How to run on Windows

### 1.1 Default: simulated payments (no Stripe account needed)

Same as Phase 2:

```powershell
docker compose up -d db
cd backend;  .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
cd frontend; $env:NUXT_PUBLIC_GRAPHQL_URL = "http://localhost:8080/graphql"; npm run dev
```

> ⚠️ **If you ran Phase 2 before:** the demo data moved from a versioned to a repeatable migration (see Bug #1), so reset your local DB once with `docker compose down -v` and then `docker compose up -d db`.

**Try the full flow (about 3 minutes):**
1. Open **Auctions**, then pick a lot that ends soon.
2. In "Test payment method", choose **Card declined** and click *Place deposit hold* → you see the decline message.
3. Choose **Visa · approved** and register → the hold is authorized → bid.
4. When the lot closes, the settlement job (every 5 s in the demo profile) creates your deal → open **My deals**.
5. Click *Pay balance* → *Confirm delivery* → the stepper reaches **Seller paid**.
6. Open **Ledger**: the trial balance is balanced and the audit chain is intact.

### 1.2 Stripe test mode (your own developer account)

**Step 1: get test keys.** Use a *developer* account at <https://dashboard.stripe.com> (not a WooPayments / Connect Express dashboard), switch on **Test mode**, then open **Developers → API keys**.

**Step 2: install the Stripe CLI** (it forwards webhooks to your laptop):
```powershell
winget install Stripe.StripeCLI      # or: scoop install stripe
stripe login                         # opens the browser once
stripe listen --forward-to localhost:8080/webhooks/stripe
# prints:  Ready! Your webhook signing secret is whsec_...   (keep this window open)
```

**Step 3: run the backend with Stripe** (new PowerShell window). Keys live in environment variables only. **Never commit them, never paste them into chat.**
```powershell
cd backend
$env:PAYMENTS_GATEWAY       = "stripe"
$env:STRIPE_SECRET_KEY      = "sk_test_..."     # Developers → API keys → Secret key
$env:STRIPE_PUBLISHABLE_KEY = "pk_test_..."     # Developers → API keys → Publishable key
$env:STRIPE_WEBHOOK_SECRET  = "whsec_..."       # from `stripe listen`
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

**Step 4: use it.** The auction page now shows a real **Stripe Elements** card field.

| Card number | Result |
|---|---|
| `4242 4242 4242 4242` | Approved |
| `4000 0000 0000 0002` | Declined |
| `4000 0027 6000 3184` | 3-D Secure challenge |

Use any future expiry date and any CVC.

**Step 5: see it in Stripe.** In Dashboard → **Payments**, a deposit hold shows as **Uncaptured**. After the auction closes it becomes **Succeeded** (winner) or **Canceled** (losers). The `stripe listen` window shows the webhooks arriving with `[200]`.

> **Honesty note:** this cloud sandbox can't reach `api.stripe.com` or `js.stripe.com`. The Stripe gateway was verified by running the **real stripe-java SDK against a local stub server** (`StripePaymentGatewayTest`), which checks paths, form fields and idempotency headers. The card form and 3-D Secure popup can only be exercised on your machine with the steps above. If something differs, send me the error text (never the keys).

---

## 2. Big picture

### 2.1 Modules after Phase 3

```
catalog ◄── rental                 shared: scalars · errors · CurrentUser · Idempotency
   ▲
   ├──── auction ──► payments ──► audit
   │        ▲            ▲          ▲
   └──── escrow ─────────┴─► ledger ┘
```
Arrows are allowed dependencies. `ModularityTests` (Spring Modulith) fails the build if a module imports another module's `internal` package or a cycle appears. `payments` knows nothing about auctions: it publishes `PaymentStatusChanged`, and `auction` and `escrow` react to it.

### 2.2 Money flow of one auction (what the ledger records)

```
REGISTER   card authorization (hold)        → no ledger entry (no money moved yet)
BID ...
HAMMER     losers: void hold                → no ledger entry
           winner: capture deposit          → DEPOSIT_CAPTURED   Dr platform_cash   Cr escrow:deal
PAY        buyer pays the balance           → BALANCE_RECEIVED   Dr platform_cash   Cr escrow:deal
CONFIRM    buyer accepts the machine        → ESCROW_RELEASED    Dr escrow:deal     Cr seller_payable (95%)
                                                                                    Cr platform_revenue (5%)
PAYOUT     bank transfer to seller          → SELLER_PAYOUT      Dr seller_payable  Cr platform_cash

After payout: escrow:deal = 0, seller_payable = 0, platform_cash = fee = platform_revenue  ✔
```
Example from the browser test ($200,000 hammer):
- $20,000 + $180,000 flows into escrow.
- $200,000 is released: $190,000 to the seller and $10,000 as the fee.
- $190,000 is paid out to the seller.

### 2.3 Escrow state machine (forward-only, one step at a time)

```
AWAITING_DEPOSIT_CAPTURE ──capture──► AWAITING_BALANCE ──pay──► FUNDED ──confirm──► RELEASED ──payout──► PAID_OUT
        (job)                              (buyer)                (buyer)               (job)
```
Each transition is a **compare-and-set**:
```sql
UPDATE escrow_deals SET state = :to WHERE id = :id AND state = :from
```
If two requests try the same step, one gets 1 row updated and the other gets 0. Optimistic concurrency, compared with the pessimistic row lock used for bids in Phase 2.

### 2.4 The saga: why settlement is NOT one big transaction

```
            ┌─ tx A (ms) ─┐          ┌── no transaction ──┐        ┌─ tx B (ms) ─┐
 settle() → │ claim lot,  │ commit → │ Stripe: capture    │ → ok → │ ledger entry│
            │ create deal │          │ key="capture:pay_x"│        │ + state move│
            └─────────────┘          └────────────────────┘        └─────────────┘
                                         ✗ crash here?
                                    job retries the same call, same key →
                                    Stripe returns the ORIGINAL result, no second capture
```
- A database transaction **cannot roll back a card capture**.
- Holding row locks during a slow HTTP call would block everyone else.
- So: record intent → call the provider outside any transaction with a deterministic key → record the result. `runPendingWork()` (every 5–15 s) finishes anything left half-done.

### 2.5 Idempotency-Key (Stripe's design, implemented ourselves)

```
first request  (user, key, hash)          → INSERT IN_PROGRESS → run → store response, COMPLETED
retry, same key + same body               → return the stored response (action NOT run again)
retry while the first is still running    → IDEMPOTENCY_IN_PROGRESS
same key, different body                  → IDEMPOTENCY_KEY_REUSED (client bug)
action failed                             → key deleted, so the user can retry
```
`payBalance` **requires** a key. The frontend generates `crypto.randomUUID()` per click.

### 2.6 Webhooks

```
Stripe ──POST /webhooks/stripe──► 1. verify HMAC-SHA256(secret, "t.payload"), reject if older than 5 min
                                  2. INSERT webhook_events(event_id)  → duplicate? 200 and do nothing
                                  3. map type → payment status, apply forward-only, in the SAME tx as 2
                                  4. return 200 fast (any non-2xx makes Stripe retry)
```
Out-of-order delivery is handled by `Payment.Status.canMoveTo()`. A late `amount_capturable_updated` arriving after `canceled` is ignored (tested).

### 2.7 Tamper-evident audit chain

```
row n:  hash_n = SHA-256( JSON[ hash_{n-1}, type, subject, actor, created_at, payload ] )
        ┌──────┐   ┌──────┐   ┌──────┐
        │ h1   │◄──│prev h1│◄──│prev h2│ ...   edit row 2 → h2 changes → row 3's prev ≠ h2 → detected
        └──────┘   └──────┘   └──────┘
```
- Appends are serialized with a transaction-scoped **advisory lock**, so concurrent writers can't fork the chain.
- `payload` is stored as **TEXT, not JSONB**: JSONB re-orders keys, which would change the hashed bytes.

---

## 3. File map

| File | Purpose | Read first? |
|---|---|---|
| `db/migration/V4__ledger_payments_escrow_audit.sql` | All Phase 3 tables + DB-enforced ledger rules | ⭐⭐⭐ |
| `escrow/internal/DefaultEscrow.java` | Saga, state machine, ledger postings, reconciliation guard | ⭐⭐⭐ |
| `payments/internal/DefaultPayments.java` | prepare → execute → apply pattern, forward-only status, events | ⭐⭐⭐ |
| `shared/Idempotency.java` | Idempotency-Key implementation | ⭐⭐⭐ |
| `payments/internal/StripeWebhookController.java` | Signature check, de-duplication, mapping | ⭐⭐ |
| `ledger/internal/JdbcLedger.java` | Balanced posting, idempotent per (kind, reference), trial balance | ⭐⭐ |
| `audit/internal/JdbcAuditTrail.java` | Hash chain append + verify | ⭐⭐ |
| `payments/internal/StripePaymentGateway.java` | PaymentIntents (manual/automatic capture), live-key guard | ⭐⭐ |
| `payments/internal/SimulatedPaymentGateway.java` | Offline gateway mirroring Stripe test tokens | ⭐ |
| `auction/internal/DefaultAuctionService.java` | Registration = deposit hold; reacts to payment events | ⭐⭐ |
| `db/demo/R__demo_data.sql` | Demo data as an idempotent repeatable migration | ⭐ |
| `test/.../escrow/EscrowFlowIT.java` | Whole money flow, concurrent settlement, decline, 3-D Secure | ⭐⭐⭐ |
| `test/.../payments/IdempotencyAndWebhookIT.java` | Key required/reused, duplicate/forged/replayed webhooks | ⭐⭐⭐ |
| `test/.../ledger/LedgerIT.java`, `audit/AuditTrailIT.java` | DB-level guarantees, tamper detection | ⭐⭐ |
| `frontend/app/components/PaymentMethodPicker.vue` | Stripe Elements or test tokens | ⭐⭐ |
| `frontend/app/pages/deals.vue`, `ledger.vue` | Buyer escrow UI, finance/ops UI | ⭐ |

---

## 4. Key concepts, explained small

### 4.1 Double-entry bookkeeping
- Every movement touches **at least two accounts**, and **debits = credits**.
- **Normal side:**
  - Assets (cash) grow with debits.
  - Liabilities (money we hold for others: escrow, seller payable) and revenue grow with credits.
- **Why fintechs insist on it:** money can't appear or vanish. If the totals don't match, there is a bug, and you know immediately (the trial balance).
- **No balance column:** a balance is `SUM(lines)`. An editable balance column is a classic source of drift and fraud.
- **Corrections are new, reversing entries**, never UPDATEs. The history is the truth.

### 4.2 Defense in depth for the ledger

| Rule | Java | PostgreSQL |
|---|---|---|
| debits = credits | `JdbcLedger.validate` | deferred constraint trigger at COMMIT |
| one side per line | `validate` | `CHECK ((debit = 0) <> (credit = 0))` |
| no edits | no update methods exist | `BEFORE UPDATE/DELETE/TRUNCATE` triggers |
| post once | `ON CONFLICT DO NOTHING` | `UNIQUE (kind, reference)` |

The Java checks give good error messages. The database checks still hold if someone bypasses Java with raw SQL (`LedgerIT.databaseRejectsAnUnbalancedEntryEvenIfJavaIsBypassed`).

### 4.3 Authorization vs capture
- **Authorize:** the bank reserves the funds; nothing moves. Cancel → the hold disappears (no refund fees).
- **Capture:** money actually moves. That's why deposits are holds: 99% of bidders lose, and voiding a hold is free, while refunding a charge is not.
- Card authorizations expire after about 7 days. Fine for short auctions; long auctions would need re-authorization.

### 4.4 Deterministic provider idempotency keys
`payment:{ourPaymentId}`, `capture:{ourPaymentId}`, `cancel:{ourPaymentId}`. Our payment row is created **before** calling Stripe, so a retry after a crash reuses the same key. That's the only reason the saga is safe.

### 4.5 Events between modules
`payments` publishes `PaymentStatusChanged` **synchronously inside its transaction**. The listeners in `auction` (registration status) and `escrow` (ledger entry + state) run in the same transaction, so the payment status, registration, ledger entry and deal state commit together or not at all.

### 4.6 Never leak existence
Asking for someone else's deal returns `NOT_FOUND`, exactly like a missing id. Otherwise an attacker could enumerate deal ids.

### 4.7 Strategy pattern for gateways
One `PaymentGateway` interface; Spring picks the implementation with `@ConditionalOnProperty`.
- **Tests and CI:** `SIMULATED` gateway, which is deterministic, offline and free.
- **Demo:** `STRIPE` gateway. The rest of the code never knows which one is running.

---

## 5. Real bugs found while building

| # | What happened | Root cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | Backend refused to start: *"Detected resolved migration not applied … outOfOrder"* | Demo data was `V100`; the new schema `V4` sorts **before** an already-applied V100 | Demo data → **repeatable** `R__demo_data.sql`, written to be idempotent | Never give seed data a high version number |
| 2 | Removed migrations still ran | `mvn package` without `clean` keeps deleted resources in `target/classes` | `mvn clean package` | Stale build outputs lie |
| 3 | `addPaymentMethodType` did not compile | Removed in stripe-java v34 | `automatic_payment_methods` with `allow_redirects=never` | Read the SDK you actually have (`javap`), not old docs |
| 4 | Append-only tests failed although the DB rejected the change | Spring wraps the SQL error; the top-level message differs | `.rootCause().hasMessageContaining(...)` | Assert on the cause you mean |
| 5 | Hydration warning on `/deals`, `/ledger` | `status` is `idle` on the server, `pending` on the client | Treat `idle` as loading | Same root cause as Phase 1 Bug #2 |
| 6 | Audit showed `REGISTRATION_CAPTURED` | Event name came from the *payment* status | Name it after the *registration* state (`REGISTRATION_APPLIED`) | Names in audit logs are a contract |

---

## 6. Evidence

| Test | Proves |
|---|---|
| `EscrowFlowIT` (7) | Full flow with exact ledger entries; double pay/confirm moves money once; strangers get NOT_FOUND; **6 concurrent settlements → 1 deal, 1 capture**; unsold → all holds voided; declined card leaves no registration; 3-D Secure stays PENDING |
| `IdempotencyAndWebhookIT` (4) | Key required; retry with same key = one charge; key reuse rejected; duplicate webhook processed once; out-of-order event ignored; forged, tampered and replayed signatures rejected |
| `LedgerIT` (5) | Balanced posting; idempotent posting; Java and DB reject unbalanced entries; append-only |
| `AuditTrailIT` (3) | Chain links; updates blocked; **admin tampering detected at the exact row** |
| `StripePaymentGatewayTest` (6) | Real SDK requests: manual vs automatic capture, idempotency headers, capture/cancel endpoints, 3-D Secure client secret, decline mapping, live keys refused |
| Browser (Chromium) | decline → approve → bid → win → settlement job → pay → confirm → PAID_OUT; trial balance balanced; chain intact |

---

## 7. Self-test

<details><summary>Q1. Why does registering create a payment row BEFORE calling Stripe?</summary>

So the provider call can use a deterministic idempotency key (`payment:{id}`). If we crash after Stripe answered but before we saved the answer, the retry sends the same key and gets the same PaymentIntent back instead of creating a second hold.
</details>

<details><summary>Q2. Why is there no "balance" column on accounts?</summary>

A stored balance can drift from the transactions or be edited. Computing it as SUM(lines) from append-only lines makes the history the single source of truth and keeps the trial balance provable.
</details>

<details><summary>Q3. Losing bidders: void or refund? Why?</summary>

Void (cancel the authorization). Nothing was captured, so nothing moves: no refund fees, no ledger entry, and the bank releases the hold.
</details>

<details><summary>Q4. A user double-clicks "Pay balance" and the first request times out on their side. Walk through what prevents a double charge.</summary>

The UI reuses the same Idempotency-Key for that action, so the second request returns the stored response (or IN_PROGRESS). Even with different keys, payBalance locks the deal row and reuses an existing non-failed balance payment. Stripe also gets the deterministic key `payment:{id}`.
</details>

<details><summary>Q5. Stripe sends `payment_intent.canceled` twice, then a late `amount_capturable_updated`. What happens?</summary>

The first event cancels the payment and inserts the event id. The duplicate hits the primary key and is acknowledged without doing anything. The late event is ignored because CANCELED → AUTHORIZED is not an allowed transition.
</details>

<details><summary>Q6. Why can't settlement be one database transaction?</summary>

It includes provider calls over the network. A DB rollback can't undo a capture, and holding locks during HTTP calls would block other work. Instead it's a saga of short, retryable steps.
</details>

<details><summary>Q7. How does the hash chain detect a deleted row?</summary>

The next row's prev_hash no longer matches the hash of the row now before it, so verify() reports the first row whose link is broken.
</details>

<details><summary>Q8. Why is the audit payload stored as TEXT and not JSONB?</summary>

JSONB normalizes (re-orders keys, strips whitespace), so the stored bytes would differ from the hashed bytes and every verification would fail.
</details>

---

## 8. Interview Q&A (Stripe / Fidelity / SIG)

**Q (Stripe): How do you make a payment API safe to retry?**
> "Clients send an Idempotency-Key per action. I store the key with a hash of the request: the first call runs and stores its response, and a retry returns the stored response. The same key with a different body is rejected, and a failure releases the key. Internally I also create our payment record before calling the provider, so the provider call uses a deterministic key and a crash mid-way can be retried without a double charge."

**Q (Fidelity): How do you guarantee the books are right?**
> "Double-entry, append-only journal. Every entry balances, enforced in Java and by a deferred constraint trigger in PostgreSQL. There's no balance column; balances are sums, and UPDATE/DELETE are blocked by triggers. Each business event can be posted once via a unique key. On top of that, a SHA-256 hash chain makes even admin-level edits detectable, and a test proves it by tampering with a row."

**Q (SIG / general): How do you handle concurrency in settlement?**
> "The auction is claimed with a compare-and-set update on `settled_at`, so only one worker wins. Deal transitions are compare-and-set on the state column. My test fires six concurrent settlements and asserts exactly one deal and one capture. For bids I used a pessimistic row lock instead, because contention there is high and fairness matters."

---

## 9. Known limitations → Phase 4

| Now | Next |
|---|---|
| Identity is the `X-User-Id` header; ledger/audit/settle are open | **Phase 4:** Auth0 JWT, roles (BUYER, ADMIN) |
| Seller payout is a simulated bank transfer | Stripe Connect transfers to sellers' connected accounts (how WooPayments pays merchants) |
| No disputes/refunds path | A DISPUTED state + reversing ledger entries |
| Balance > $999,999.99 rejected (card limit) | Wire/ACH flow with manual reconciliation |
| Daily reconciliation against provider reports not implemented | A recon job comparing captured payments with `platform_cash` movements |
| Single instance (in-memory subscription sink, scheduled jobs) | **Phase 4:** discuss LISTEN/NOTIFY and job locking for multiple replicas |

---

## 10. How to study this phase

**Principles:**
1. **Generation:** Before reading `DefaultEscrow`, write the 4 journal entries of a $100,000 sale (10% deposit, 5% fee) on paper. Then run `EscrowFlowIT` and compare.
2. **Retrieval:** Redraw diagram 2.4 (saga) from memory tomorrow.
3. **Elaboration:** Explain authorization vs capture to a friend in English, in under 60 seconds.

**Concrete drills (on your Windows machine):**
- 🔁 **Break-it:** In `DefaultPayments.execute`, replace the key `"payment:" + p.id()` with `UUID.randomUUID()`. Which guarantee is lost? (Answer: retry-after-crash safety. No test catches it yet. Can you write one?)
- 🔁 **Break-it:** Delete the `canMoveTo` check in `DefaultPayments.transition` → `IdempotencyAndWebhookIT` fails on the out-of-order event.
- 🔁 **Tamper:** In psql, run `ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only;`, update one payload, then open `/ledger` → "Broken at #n". Restore it afterwards.
- 🔁 **Stripe:** Run section 1.2, watch the PaymentIntent go *Uncaptured → Succeeded* in the dashboard, and see the webhooks in the `stripe listen` window.

---

## 11. PR text to paste (you open the PR yourself)

**Title:**
```
Phase 3: Payments (Stripe test mode), escrow settlement, double-entry ledger, audit hash chain
```

**Body:**
```markdown
## Summary
- PaymentGateway strategy: SIMULATED (default, mirrors Stripe test tokens) and STRIPE (PaymentIntents, manual capture for deposit holds; refuses live keys).
- Escrow saga: settlement job claims ended lots, voids losing holds, captures the winner's deposit; buyer pays balance and confirms delivery; seller payout minus 5% fee. Forward-only, compare-and-set state machine.
- Double-entry ledger: balanced entries enforced in Java and by a deferred PostgreSQL trigger; append-only; idempotent per (kind, reference); trial balance.
- Idempotency-Key for money-moving mutations (required for payBalance); deterministic provider idempotency keys.
- Stripe webhooks: HMAC signature + timestamp tolerance, de-duplication by event id, out-of-order safe.
- SHA-256 hash-chained audit log with verification; tampering detected even with triggers disabled.
- Demo data moved to an idempotent repeatable migration (R__) to fix out-of-order versioning.
- Frontend: payment method picker (Stripe Elements or test tokens), My deals page with escrow stepper and per-deal journal, Ledger page with trial balance and audit chain status.

## Testing
- Backend: 35 unit tests (incl. real stripe-java SDK against a local stub) + 31 Testcontainers integration tests
- Frontend: 57 unit tests, typecheck clean
- Verified in Chromium end to end: declined card, approved hold, bid, settlement, pay balance, confirm delivery, ledger balanced, audit chain intact

## Docs
- docs/handover/phase-3.md (includes Stripe test-mode setup on Windows)
```
