# QuipMarket Capstone: Project Summary, Resume Material & Interview Kit

> Everything you need to **present** this project: a 60-second pitch, the architecture on one page, resume bullets tailored per company, a STAR story bank, a 5-minute demo script, and what *not* to claim.

---

## 1. The 60-second pitch

> "QuipMarket is a B2B marketplace for heavy equipment: live auctions, rentals, and escrow settlement. The frontend is Nuxt 4 with SSR; the backend is a Spring Boot 4 GraphQL modular monolith on PostgreSQL.
>
> The interesting part is correctness with money and concurrency:
> - A PostgreSQL exclusion constraint makes double-booking impossible, and a per-lot row lock serializes bids. Both are proven with 40- to 50-thread race tests.
> - Winning bidders' deposits are card authorizations through Stripe, and settlement runs as a saga with idempotency keys, so retries never double-charge.
> - Every dollar is a balanced double-entry journal entry enforced by the database itself, and every event goes into a SHA-256 hash-chained audit log.
>
> It's containerized, tested in CI with Testcontainers, and has Kubernetes manifests and OpenTofu for deployment. Auth is Auth0 with role-based access, and there's a token-bucket rate limiter."

---

## 2. Architecture on one page

```
             ┌──────────── Nuxt 4 (SSR, TypeScript) ─────────────┐
             │ catalog · auction room (live) · rentals · deals ·  │
             │ ledger · Stripe Elements · Auth0 SPA login (PKCE)  │
             └───────────────┬──────────────────────┬─────────────┘
                 GraphQL HTTP │ (Bearer JWT,          │ GraphQL WebSocket
                 Idempotency-Key)                     │ subscriptions
┌─────────────────────────────▼──────────────────────▼────────────────────────────┐
│ Spring Boot 4 · Java 21 · modular monolith (Spring Modulith-verified)             │
│ Security (Auth0 JWT, roles) → RateLimit (token bucket) → GraphQL (depth/complexity)│
│                                                                                    │
│  catalog ◄─ rental (EXCLUDE constraint)        shared: errors · identity · idempotency
│     ▲                                                                              │
│     ├── auction (proxy bidding, FOR UPDATE, LISTEN/NOTIFY) ─► payments ─► audit    │
│     └── escrow (saga, CAS state machine) ──────────┴────► ledger ┘                 │
│                                      payments: SIMULATED | STRIPE (manual capture, │
│                                                signed + de-duplicated webhooks)    │
└──────────────────────────────────────────┬─────────────────────────────────────────┘
                                           ▼
             PostgreSQL 16 · Flyway — bookings EXCLUDE · journal (balanced, append-only)
             · audit_log (hash chain) · idempotency_keys · webhook_events · pg_notify
```

**By the numbers:** about 4,300 lines of Java, 1,600 lines of Java tests, 5,000 lines of TypeScript/Vue, and 300 lines of SQL.
- Backend: **82 tests** (41 unit + 41 integration against a real PostgreSQL).
- Frontend: **57 unit tests**.
- **4 phases** with a handover doc each.

---

## 3. Resume bullets (pick 3–4 per application)

**General / MassQuip (full stack, Nuxt + Spring GraphQL):**
- Built a heavy-equipment auction and rental marketplace with a **Nuxt 4 SSR** frontend and a **Spring Boot 4 GraphQL** modular monolith on **PostgreSQL**, with real-time bidding over GraphQL subscriptions.
- Eliminated GraphQL **N+1 queries** with DataLoader batching; a test counts SQL statements to keep nested lists at a constant number of queries.
- Containerized with multi-stage, non-root Docker images; **GitHub Actions** CI runs Testcontainers integration tests and publishes images; deployment defined with **Kubernetes** manifests and **OpenTofu** (Linode LKE, managed Postgres, Cloudflare).

**Stripe (payments, API design, reliability):**
- Designed **idempotent payment mutations** (Idempotency-Key with request-hash verification) and a settlement **saga** with deterministic provider idempotency keys, so client retries and crash recovery never charge twice.
- Integrated **Stripe PaymentIntents** with manual capture for refundable auction deposits; processed **signature-verified, de-duplicated webhooks** that tolerate out-of-order delivery.
- Protected the public API with a **token-bucket rate limiter** (HTTP 429 + Retry-After) and GraphQL depth/complexity limits.

**Fidelity (financial correctness, Java/Spring, audit):**
- Implemented a **double-entry ledger** whose invariants (balanced entries, append-only, post-once) are enforced by **PostgreSQL triggers and constraints**, not only application code.
- Built a **tamper-evident audit trail** using SHA-256 hash chaining; tests prove even DBA-level edits are detected at the exact record.
- Secured the API with **Auth0 (OAuth2/JWT)**, including issuer and audience validation and role-based access to financial data.

**SIG (concurrency, correctness, data structures):**
- Guaranteed **zero double-bookings under concurrency** with a PostgreSQL exclusion constraint, verified by a 50-thread race test.
- Serialized concurrent auction bids with row-level locking; demonstrated the **lost-update anomaly** by removing the lock and watching a 40-thread test fail.
- Implemented **proxy bidding with price-time priority** and anti-sniping soft close; implemented the same engine in TypeScript and Java against shared test cases, including a 2,000-step randomized invariant test.

---

## 4. STAR story bank

| # | Situation / Task | Action | Result | Use for |
|---|---|---|---|---|
| 1 | Bids on the same lot could arrive at the same moment | Row lock `SELECT … FOR UPDATE` per lot; wrote a 40-thread race test; **removed the lock on purpose** to confirm the test catches lost updates | Test fails without the lock (wrong winner), passes with it | SIG, any backend |
| 2 | Double-booking risk for rentals | Rejected check-then-insert; used a PostgreSQL `EXCLUDE USING gist` constraint on half-open date ranges | 50 concurrent requests → exactly 1 booking; the constraint also caught overlapping rows in my own seed data | SIG, Fidelity |
| 3 | Money must never move twice | Idempotency-Key table + payment row recorded before calling Stripe + deterministic provider keys + forward-only states | Retries return the stored response; tests confirm one charge | Stripe |
| 4 | Books must be provably correct | Double-entry with a deferred constraint trigger; append-only triggers; trial balance; hash-chained audit | Raw SQL bypassing Java is still rejected; tampering detected at the exact row | Fidelity |
| 5 | Production bug class: migrations | Seed data numbered V100 blocked a new V4 schema migration ("out of order") | Moved seed data to an idempotent repeatable `R__` migration | Any backend |
| 6 | Subtle Spring bug | A method called on `this` silently skipped `@Transactional` (self-invocation) | Explicit TransactionTemplate; explained the proxy model | Java/Spring interviews |
| 7 | Resilience to a third party | Public pages hung when Auth0 was unreachable | Switched to a non-blocking client; public data never waits on the identity provider | Stripe, MassQuip |

Tip: for each story, prepare **one number** (40 threads, 13 of 150 accepted, 82 tests) and **one trade-off** (pessimistic vs optimistic lock, LISTEN/NOTIFY vs Kafka).

---

## 5. Five-minute demo script

1. **(30 s) Catalog:** filter "cat", show the URL updating, open a detail page (SSR; view source shows the title).
2. **(60 s) Auction room:** register with **"Card declined"** → error; then **Visa** → hold authorized; bid; open a second tab and bid as a bot or second user → the first tab updates live.
3. **(60 s) Escrow:** once a lot ends, open **My deals** → Pay balance → Confirm delivery → stepper reaches *Seller paid*.
4. **(60 s) Ledger:** trial balance *Balanced*; show the four entries of the deal; audit chain *Intact*.
5. **(60 s) Engineering:** open `BookingConcurrencyIT` (50 threads) and `EscrowFlowIT`; show the green **Actions** run; open `k8s/backend.yaml` (probes, non-root).
6. **(30 s) Close:** "Happy to walk through the saga or the locking strategy in more depth."

Run it locally with `docker compose --profile app up --build`. Practice twice with a timer.

---

## 6. What NOT to claim (stay credible)

- ❌ "Production system with real users / real payments" → ✅ "A production-style portfolio project; Stripe in **test mode**."
- ❌ "High-frequency trading" → ✅ "Correctness under concurrent access, verified with race tests."
- ❌ "Deployed on Kubernetes" (unless you actually ran `tofu apply`) → ✅ "Kubernetes manifests and OpenTofu, validated in CI."
- ❌ "I wrote every line alone without tools" → ✅ "I built it with AI assistance and can explain and modify every part." (Then make sure you can: do the drills in each handover doc.)

---

## 7. Upwork proposal for MassQuip (updated)

> Hi MassQuip team,
>
> I built a working demo of a heavy-equipment marketplace on your exact stack (**Nuxt** frontend, **Spring Boot + GraphQL** modular monolith, **PostgreSQL/Flyway**, **Auth0**, Docker/Kubernetes, OpenTofu for Linode): **[live demo link]** · **[GitHub link]**
>
> It covers live auctions with proxy bidding, rentals with double-booking prevention, and escrow payments through Stripe test mode, with a double-entry ledger. The repo has CI with Testcontainers, Kubernetes manifests with probes, and OpenTofu for LKE + managed Postgres + Cloudflare.
>
> I'm a CS master's student, available 1–5 hours/week, comfortable working in ClickUp. Happy to start on frontend tickets and help across the stack.
>
> Quick question: will rentals and sales share one listing model, or will you keep them as separate flows?

---

## 8. Where to go next

1. **Deploy the frontend demo** (Cloudflare Pages, mock mode, free) and put the link in the README and proposals.
2. Do the **break-it drills** in each handover doc until you can explain every failure without notes.
3. Optional SIG-style side project (from the original plan): an **order-book matching engine** in Java with JMH latency percentiles.
4. Keep NeetCode practice daily. Projects get you interviews, and online assessments decide them.
