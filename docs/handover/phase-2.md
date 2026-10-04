# Phase 2 Handover: Spring Boot GraphQL Backend + PostgreSQL

> **Status:** ✅ Complete · 29 unit tests + 12 integration tests (real PostgreSQL via Testcontainers) · module boundaries verified · frontend connected end to end (HTTP + WebSocket) · checked in Chromium
>
> **Folders:** `backend/` (new), `frontend/app/services/graphql/` (new), `compose.yaml` (new)

---

## 0. TL;DR

The auction engine, rentals, catalog and financing now run on a **Java 21 / Spring Boot 4.1 / Spring for GraphQL** server backed by **PostgreSQL 16 + Flyway**. Correctness under concurrency is guaranteed by the **database**, and proven by tests that fire 40–50 simultaneous requests:
- **Double-booking is impossible:** a PostgreSQL `EXCLUDE` constraint rejects overlapping periods atomically.
- **Bids can't overwrite each other:** each lot's row is locked (`SELECT … FOR UPDATE`) for the duration of a bid.
- **No N+1:** `@BatchMapping` loads nested data in one query per type, and a test counts the SQL statements.
- **Live updates:** GraphQL subscriptions over WebSocket push every committed bid to every open browser.

The Nuxt app switches from mock data to the API with **one environment variable**, and no page changed.

![live auction](../screenshots/10-live-auction.png)

---

## 1. How to run on Windows

**Install once**

| Tool | Where | Check |
|---|---|---|
| JDK 21 (Temurin) | <https://adoptium.net> | `java -version` → 21 |
| Docker Desktop | <https://www.docker.com/products/docker-desktop> | `docker version` |
| Node 22 | (from Phase 1) | `node -v` |

> Maven is **not** required: `mvnw.cmd` downloads the right version automatically.

**Run the whole stack (3 terminals)**

```powershell
# Terminal 1 — database (from repo root)
docker compose up -d db

# Terminal 2 — backend, demo profile = seed data + live auctions + rival bots
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
#   GraphiQL (browser IDE):  http://localhost:8080/graphiql
#   Schema:                   http://localhost:8080/graphql/schema

# Terminal 3 — frontend pointed at the API
cd frontend
$env:NUXT_PUBLIC_GRAPHQL_URL = "http://localhost:8080/graphql"
npm run dev        # http://localhost:3000  → footer says "Data: live GraphQL API"
```

**Tests**

| Command (in `backend/`) | Runs | Needs Docker? |
|---|---|---|
| `.\mvnw.cmd test` | 29 fast unit tests + module-boundary check | No |
| `.\mvnw.cmd verify` | the above + 12 integration tests on a real PostgreSQL | **Yes** |

**Try it in GraphiQL** (Headers tab: `{"X-User-Id": "alice"}`)

```graphql
mutation {
  registerToBid(auctionId: "au-2001") { status depositCents }
  placeBid(auctionId: "au-2001", maxCents: 25000000) { accepted leading reason auction { currentPriceCents myMaxCents } }
}
```

---

## 2. Big picture

### 2.1 Modules (modular monolith)

```
            ┌──────────────────────────── one Spring Boot process ────────────────────────────┐
 Nuxt ──────►  /graphql (HTTP)        /graphql-ws (WebSocket subscriptions)                   │
            │        │                                                                         │
            │  shared  (open): scalars Long/DateTime/Date · error codes · CurrentUser · Clock  │
            │        │                                                                         │
            │  ┌─────▼─────┐      ┌──────────┐      ┌──────────┐      ┌────────────┐           │
            │  │  catalog  │◄─────│  rental  │      │ auction  │─────►│  catalog   │           │
            │  │ Equipment │      │ EXCLUDE  │      │ FOR UPDATE│      │ (facade)   │           │
            │  └───────────┘      └──────────┘      └──────────┘      └────────────┘           │
            │                                         financing (no deps, pure BigDecimal)    │
            │  Rules: arrows = allowed dependencies; nobody may import another module's       │
            │  `internal` package. ModularityTests fails the build otherwise.                 │
            └───────────────────────────────────────┬──────────────────────────────────────────┘
                                                    ▼
                              PostgreSQL 16  (Flyway V1 catalog · V2 rentals · V3 auctions)
```

**Why a modular monolith?** It's the architecture MassQuip uses. One deployable keeps operations simple, while enforced boundaries keep the code splittable into services later.

### 2.2 One bid, inside the server

```
placeBid(au-2001, alice, $250k)                         ── @Transactional ──────────────┐
  │                                                                                       │
  ├─1─ SELECT … FROM auctions WHERE id='au-2001' FOR UPDATE   ◄── other bids on THIS lot │
  │                                                              wait here (serialized)   │
  ├─2─ registration exists?            no → NOT_REGISTERED error                         │
  ├─3─ AuctionEngine.placeBid(state, alice, max, clock.instant())   (pure function)      │
  ├─4─ UPDATE auctions … ; INSERT auction_bids … ; INSERT bid_submissions (audit)        │
  └─5─ register afterCommit hook ─────────────────────────────────────────────────────────┘
                                         COMMIT → lock released → next waiting bid runs
                                            │
                                            ▼ afterCommit
                              Sinks.Many<String> ──► every subscription for au-2001
                                                    re-reads the lot → pushes to browsers
```

**Why publish AFTER commit?** If we pushed before commit and the transaction rolled back, browsers would show a bid that never happened.

### 2.3 Lost update: what the lock prevents (proven by a test)

```
WITHOUT the row lock                      WITH  SELECT … FOR UPDATE
T1: read leader=A(max 170k)               T1: read+LOCK leader=A
T2: read leader=A(max 170k)               T2: read … ⏳ waits for T1
T1: bob max 180k → leader=bob, write      T1: bob 180k → leader=bob, COMMIT
T2: carol max 175k → leader=carol, write  T2: (now reads leader=bob 180k)
    ❌ bob's higher bid silently lost          carol 175k < 180k → bob stays ✅
```

I removed `FOR UPDATE` on purpose and re-ran `AuctionConcurrencyIT`. The test **failed** (`expected bidder-29 but was bidder-39`), which proves the test really detects the race. Then the lock was restored.

### 2.4 Double booking: why no Java lock is needed

```sql
CONSTRAINT bookings_no_overlap EXCLUDE USING gist (equipment_id WITH =, period WITH &&)
```
"No two rows may have **equal** `equipment_id` **and overlapping** (`&&`) `period`." PostgreSQL checks this **inside** the INSERT, atomically.
- ❌ **Check-then-insert in Java** = a race: two requests both see "free", and both insert.
- ✅ **Constraint**: 50 concurrent requests → exactly **1** row and **49** × `BOOKING_CONFLICT` (`BookingConcurrencyIT`).

`daterange(start, end, '[)')` is half-open, the same convention as the frontend, so back-to-back bookings are legal.

### 2.5 N+1 → batching

```
query { auctions { id equipment { title } } }

naive resolver:  SELECT auctions            (1)
                 SELECT equipment WHERE id=? (× N auctions)   → 1 + N queries
@BatchMapping:   SELECT auctions            (1)
                 SELECT equipment WHERE id IN (…all…)  (1)   → 2 queries, for any N
```
`GraphQlApiIT.nestedListsAreBatchedNotNPlusOne` wraps the `DataSource` to **count SQL statements** and asserts exactly 2 (and exactly 3 for `equipment { bookings activeAuction }` over 12 machines).

---

## 3. File map

| File | Purpose | Read first? |
|---|---|---|
| `backend/src/main/resources/graphql/schema.graphqls` | The API contract | ⭐⭐⭐ |
| `backend/src/main/resources/db/migration/V2__rentals.sql` | `EXCLUDE` constraint | ⭐⭐⭐ |
| `backend/src/main/resources/db/migration/V3__auctions.sql` | Auction tables + CHECK invariants | ⭐⭐ |
| `auction/AuctionEngine.java` | Proxy bidding rules (Java port, pure) | ⭐⭐⭐ |
| `auction/internal/DefaultAuctionService.java` | Transaction, lock, publish after commit | ⭐⭐⭐ |
| `auction/internal/AuctionRepository.java` | SQL incl. `FOR UPDATE`, idempotent `ON CONFLICT DO NOTHING` | ⭐⭐ |
| `auction/internal/AuctionGraphQlController.java` | Queries, mutations, subscription, `@BatchMapping`, secret-max resolver | ⭐⭐ |
| `rental/internal/BookingRepository.java` | Insert + translate SQLSTATE `23P01` → `BOOKING_CONFLICT` | ⭐⭐ |
| `rental/RentalPricing.java` | LC 983 DP in Java | ⭐ |
| `financing/LoanCalculator.java` | `BigDecimal` + `HALF_UP`, matches the frontend to the cent | ⭐ |
| `shared/GraphQlErrorMapping.java` | Exceptions → GraphQL errors with `extensions.code` | ⭐ |
| `shared/CurrentUser.java` | Demo identity (`X-User-Id` / WS init payload) | ⭐ |
| `shared/GraphQlScalars.java` | `Long`, `DateTime`, `Date` scalars | ⭐ |
| `auction/internal/demo/DemoAuctions.java` | Demo profile: reseeds lots relative to now + rival bots | |
| `src/test/.../support/TestInfrastructure.java` | Testcontainers Postgres, mutable clock, SQL counter | ⭐⭐ |
| `src/test/.../*IT.java` | Concurrency + API contract tests | ⭐⭐⭐ |
| `frontend/app/services/graphql/*.ts` | GraphQL implementations of `EquipmentApi` / `AuctionApi` | ⭐⭐ |
| `compose.yaml` | Local PostgreSQL | |

---

## 4. Key concepts, explained small

### 4.1 `JdbcClient` (plain SQL) instead of JPA
- **What:** Spring 6's fluent SQL API with named parameters.
- **Why here:** this system's correctness depends on **specific SQL**: `FOR UPDATE`, `EXCLUDE`, `ON CONFLICT DO NOTHING`, `DISTINCT ON`. With JPA these hide behind annotations and can be surprising (lazy loading → hidden N+1).
- **Trade-off:** more code for mapping rows, but every query is visible and reviewable.
- **Interview line:** *"For a money and inventory system I prefer explicit SQL, so locks and constraints are visible in code review."*

### 4.2 Pessimistic lock vs optimistic lock

| | Pessimistic `FOR UPDATE` (used for bids) | Optimistic `version` column |
|---|---|---|
| How | Lock the row, others wait | Read version, `UPDATE … WHERE version = ?`, retry on 0 rows |
| Best when | **High contention** on one row (a hot auction in its last minute) | Low contention |
| Cost | Waiting | Retries / failed requests |

A hot lot has many bidders at once → pessimistic is simpler and fair.

### 4.3 Why not a distributed lock (Redis / Redlock)?
The database is already the single source of truth, and the row lock lives **in the same transaction** as the write. A Redis lock would be a second source of truth, and if it expires mid-transaction, two writers can still collide. **Rule:** lock where the data lives.

### 4.4 Errors vs. data in GraphQL
- **Expected outcome** (bid too low, outbid) → **data**: `BidResult { accepted: false, reason: TOO_LOW, minimumCents }`.
- **Invalid request** (not registered, overlapping booking, unknown id) → **error** with `extensions.code`.
- Clients switch on `code`, never on the message text, so messages can change freely.

### 4.5 Secret max: field-level privacy
`leaderMaxCents` lives in `AuctionView` but is **not in the schema**, so GraphQL can't serialize it. `myMaxCents` is a resolver that returns the value **only if the caller is the leader** (tested in `secretMaxIsOnlyVisibleToTheLeader`). The reserve price is never sent at all: the API exposes only `hasReserve` and `reserveMet`.

### 4.6 Inject `Clock`, never call `Instant.now()`
Tests freeze time at `2026-10-01T12:00Z` and then call `clock.advance(5 min)` to test "auction ended", with no `Thread.sleep`.

### 4.7 Testcontainers
Integration tests start a **real PostgreSQL 16** in Docker. An in-memory H2 database would not support `EXCLUDE`, `daterange`, `FOR UPDATE` semantics or `DISTINCT ON`, and would give false confidence.

### 4.8 Idempotent registration
`INSERT … ON CONFLICT (auction_id, bidder_id) DO NOTHING` + a primary key → clicking "register" twice (or a network retry) can never hold the deposit twice. Phase 3 generalizes this with **Idempotency-Key** for payments.

### 4.9 Demo data vs. real migrations
`db/migration` = schema, which runs everywhere. `db/demo` = sample inventory, which runs only with `--spring.profiles.active=demo`. Production never gets fake listings.

---

## 5. Real bugs found while building

| # | What happened | Root cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | Seed data **violated** the new `EXCLUDE` constraint | The Phase 1 mock had two overlapping bookings for eq-1004 | Fixed the mock (Oct 10–17 → Oct 17–24) | A DB constraint finds bad data that JS code silently accepted |
| 2 | Live updates didn't arrive; WebSocket **403** | The WS handshake checks `Origin` separately from HTTP CORS | Use `spring.graphql.cors.*` (covers both) | CORS for WebSocket is its own thing |
| 3 | Bots would have ignored the on/off switch | `@ConditionalOnProperty` on a **method** does nothing (it's for bean definitions) | Read the flag with `@Value` | Know where an annotation actually applies |
| 4 | `mvn test` showed only 29 tests | `*IT` classes are run by **Failsafe**, not Surefire | Added `maven-failsafe-plugin` → `mvn verify` | Fast unit tests vs slower integration tests |
| 5 | My first end-to-end check passed **without proving anything** | The user was already leading, so the price couldn't change | New test: another user bids through the API, and the open browser updates without reload | A test that can't fail proves nothing; check that it *can* fail |
| 6 | (Found later, by CI on `main`) the 50-thread booking test failed **once**: `deadlock detected` instead of `BOOKING_CONFLICT` | An `EXCLUDE` constraint is checked *after* the insert, so two transactions inserting overlapping rows at the same instant can each wait for the other's uncommitted row; a local stress run hit it in 8 of 30 rounds | `pg_advisory_xact_lock` per machine before the insert (the constraint still guarantees correctness); the test now runs 10 rounds and fails without the lock | An intermittent CI failure is a real bug until proven otherwise: reproduce it, fix it, make the test catch it |

---

## 6. Evidence

| Test | Proves |
|---|---|
| `AuctionEngineTest` (16) | Same proxy-bidding rules as the TS engine, plus 2,000-step random invariant check |
| `RentalPricingTest` (7) | DP total = reconstructed breakdown for every length 1–365 days |
| `LoanCalculatorTest` (5) | Java = TypeScript to the cent ($2,621.39/month, $25,683.22 interest) |
| `ModularityTests` (1) | No module touches another's `internal` package, no cycles |
| `BookingConcurrencyIT` (3) | 50 simultaneous bookings → exactly 1 wins |
| `AuctionConcurrencyIT` (3) | 40 simultaneous bids → the correct leader, every attempt audited; soft close; idempotent registration |
| `GraphQlApiIT` (6) | Server-side filters, **SQL statement counts**, secret max privacy, error codes, data-vs-error contract |

---

## 7. Self-test

> Answer aloud in English first, then open.

<details><summary>Q1. Why can't you prevent double booking with "SELECT overlapping; if none, INSERT" in Java?</summary>

Two transactions can both run the SELECT before either INSERTs, and both see "free". It's a check-then-act race. The EXCLUDE constraint makes the check part of the insert itself, atomically.
</details>

<details><summary>Q2. What does FOR UPDATE lock, and why does it not slow down bids on other auctions?</summary>

It locks only the selected row (that auction). Bids on other lots lock other rows, so they run in parallel. Contention is per lot.
</details>

<details><summary>Q3. Name the anomaly the auction concurrency test detects without the lock.</summary>

Lost update: two transactions read the same old leader, and the later write overwrites the earlier, higher bid.
</details>

<details><summary>Q4. Why are subscription events emitted afterCommit, not right after the UPDATE?</summary>

If the transaction rolled back, clients would have seen a bid that doesn't exist. Also, a reader on another connection can't see uncommitted data yet.
</details>

<details><summary>Q5. A "too low" bid returns accepted=false (data), but "not registered" returns an error. Why the difference?</summary>

"Too low" is a normal, expected outcome the UI renders. "Not registered" means the client skipped a required step: an invalid request with a machine-readable code.
</details>

<details><summary>Q6. Why does the API need a custom Long scalar for money?</summary>

GraphQL Int is a signed 32-bit integer, so its maximum is about 2.1 billion cents ≈ $21M. Sums and big cranes need 64-bit.
</details>

<details><summary>Q7. The subscription Sink is in-memory. What breaks with 3 backend replicas, and how would you fix it?</summary>

A bid handled by replica A only notifies browsers connected to A. Fix: broadcast through PostgreSQL LISTEN/NOTIFY, Redis pub/sub or Kafka, so every replica pushes to its own clients.
</details>

<details><summary>Q8. Why test against a real PostgreSQL instead of H2?</summary>

The guarantees depend on PostgreSQL features (EXCLUDE with GiST, daterange, row-lock semantics, DISTINCT ON). H2 doesn't support them, so the tests would prove nothing about production.
</details>

---

## 8. Interview Q&A (say these in English)

**Q: How do you prevent two people renting the same excavator on the same day?**
> "With a PostgreSQL exclusion constraint on equipment ID and a half-open date range. The database rejects overlaps atomically, so there's no race between checking and inserting. I verified it with a test that fires 50 concurrent requests: exactly one succeeds and the other 49 get a BOOKING_CONFLICT error code."

**Q: How do you handle concurrent bids?**
> "Each bid runs in a transaction that takes a row lock on that auction with SELECT FOR UPDATE, so bids on the same lot are serialized but different lots run in parallel. I proved the lock matters by removing it: the 40-thread test then picked the wrong winner because of a lost update."

**Q: What's the N+1 problem in GraphQL and how did you solve it?**
> "Resolving a nested field per parent causes one query per item. I used Spring GraphQL's @BatchMapping, which is backed by DataLoader, so each nested type loads in a single IN query. A test counts SQL statements to make sure it stays that way."

---

## 9. Known limitations → next phases

| Now | Next |
|---|---|
| Identity is an `X-User-Id` header (trust-me demo) | **Phase 4:** Auth0 JWT, roles |
| Deposit "hold" is a DB row; no money moves | **Phase 3:** Stripe test mode (manual capture) + double-entry ledger |
| Ended auctions aren't settled (holds not released/applied) | **Phase 3:** settlement job + ledger entries + escrow |
| `bid_submissions` is append-only but not tamper-evident | **Phase 3:** SHA-256 hash chain audit log |
| No idempotency key on mutations | **Phase 3:** `Idempotency-Key` for payment mutations |
| Subscriptions are single-instance | **Phase 4:** discuss LISTEN/NOTIFY for multi-replica |
| No rate limiting or query depth limits | **Phase 4** |

---

## 10. How to study this phase

**Principles:**
1. **Generation:** Before opening `DefaultAuctionService.placeBid`, write the 5 steps from diagram 2.2 on paper, then compare.
2. **Elaboration:** Explain diagram 2.3 to yourself in English, as if to a Stripe interviewer.
3. **Interleaving:** On the same day, do NeetCode *Merge Intervals* and re-read `V2__rentals.sql`. It's the same idea, once in code and once in the database.

**Concrete drills (do these on your Windows machine):**
- 🔁 **Break-it drill:** Delete `FOR UPDATE` in `AuctionRepository.lockById` → `.\mvnw.cmd verify` → watch `AuctionConcurrencyIT` fail → restore it.
- 🔁 **Break-it drill 2:** Comment out the `EXCLUDE` line in `V2__rentals.sql` (with a fresh DB) → `BookingConcurrencyIT` now creates many bookings.
- 🔁 **N+1 drill:** Replace `@BatchMapping equipment(...)` with a `@SchemaMapping` that calls `catalog.findById` → the statement-count assertion fails. Then read the number it reports.
- 🔁 **GraphiQL drill:** Bid as `alice`, then query `myMaxCents` as `bob`. Why is it `null`?

---

## 11. PR text to paste (you open the PR yourself)

**Title:**
```
Phase 2: Spring Boot GraphQL backend with PostgreSQL, concurrency-safe auctions and rentals
```

**Body:**
```markdown
## Summary
- Spring Boot 4.1 / Java 21 modular monolith (catalog, rental, auction, financing) with Spring for GraphQL.
- PostgreSQL 16 + Flyway; double booking prevented by an EXCLUDE (GiST) constraint on half-open date ranges.
- Bids serialized per lot with SELECT … FOR UPDATE; events published after commit to GraphQL subscriptions (WebSocket).
- @BatchMapping for nested fields (no N+1); custom Long/DateTime/Date scalars; machine-readable error codes.
- Frontend: GraphQL implementations of EquipmentApi/AuctionApi, enabled via NUXT_PUBLIC_GRAPHQL_URL.
- Demo profile seeds lots relative to now and runs rival bidder bots.

## Testing
- `./mvnw test`: 29 unit tests + Spring Modulith boundary verification
- `./mvnw verify`: 12 Testcontainers integration tests (50-way booking race, 40-way bid race, SQL statement counts, API contract)
- Frontend: 57 unit tests, typecheck clean; verified in Chromium against the live API, including subscription push without reload

## Docs
- docs/handover/phase-2.md
```
