# QuipMarket

**Heavy-equipment auctions, rentals & escrow settlement**, a B2B marketplace where bids can't race, bookings can't double-book, and every dollar is traceable through a double-entry ledger.

> 重型设备 B2B 交易平台：在线竞拍、按天租赁、托管结算。出价不会冲突、预订不会重叠，每一分钱都能在复式账本里追溯。

| Layer | Tech |
|---|---|
| Frontend | Nuxt 4 (Vue 3, TypeScript, SSR) |
| Backend | Java 21, Spring Boot 4.1, Spring for GraphQL (queries, mutations, WebSocket subscriptions), modular monolith verified by Spring Modulith |
| Data | PostgreSQL 16, Flyway, plain SQL via `JdbcClient` |
| Payments *(Phase 3)* | `PaymentGateway` interface: simulated + Stripe test mode |
| Platform *(Phase 4)* | Auth0, Docker, GitHub Actions, Kubernetes, OpenTofu |

## Features

- **Catalog**: filter and sort heavy equipment; filters live in the URL, so they are shareable and survive a refresh.
- **Live auctions**: proxy (max) bidding, price-then-time priority, 2-minute soft close against sniping, reserve prices.
- **Escrow deposits**: bidders hold a refundable deposit before bidding.
- **Rentals**: the cheapest day/week/month mix (dynamic programming), overlap detection, and a suggested next free window.
- **Financing**: an amortization schedule computed in integer cents, plus a buy-vs-rent break-even.
- **Concurrency-safe by construction**: a PostgreSQL `EXCLUDE` constraint makes double booking impossible, and a per-lot row lock serializes bids. Both are proven by 40–50-thread race tests.
- **No N+1**: nested GraphQL fields are batched, and a test counts SQL statements.

## Run it

**Frontend only (mock data, no backend needed):**
```bash
cd frontend
npm install
npm run dev      # http://localhost:3000
npm test         # 57 unit tests (Vitest)
```

**Full stack (needs Docker + JDK 21):**
```bash
docker compose up -d db
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # :8080, GraphiQL at /graphiql
cd frontend && NUXT_PUBLIC_GRAPHQL_URL=http://localhost:8080/graphql npm run dev
cd backend && ./mvnw verify   # 29 unit + 12 Testcontainers integration tests
```
Windows (PowerShell) commands are in [docs/handover/phase-2.md](docs/handover/phase-2.md#1-how-to-run-on-windows--在-windows-上运行).

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Nuxt frontend with mock data | ✅ [handover](docs/handover/phase-1.md) |
| 2 | Spring Boot GraphQL + PostgreSQL: catalog, rentals (EXCLUDE constraint), authoritative auction engine, live updates | ✅ [handover](docs/handover/phase-2.md) |
| 3 | Ledger, escrow, idempotency, Stripe test mode, webhooks, audit hash chain | ⏳ |
| 4 | Auth0, rate limiting, Docker, CI, Kubernetes, OpenTofu | ⏳ |
