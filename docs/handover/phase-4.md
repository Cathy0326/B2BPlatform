# Phase 4 Handover: Auth0, API Protection, Containers, CI, Kubernetes, OpenTofu

> **Status:** ✅ Complete · backend 41 unit + 41 integration tests · frontend 57 unit tests · both Docker images built and the full stack verified running from them · Kubernetes manifests pass strict schema validation (k8s 1.33)
>
> **New:** `SecurityConfig`, `CurrentUser` (rewritten), `RateLimitFilter` + `TokenBucket`, query depth/complexity limits, `AuctionChangeNotifier` (LISTEN/NOTIFY), `Dockerfile` ×2, `compose.yaml` (full stack), `.github/workflows/ci.yml`, `k8s/`, `infra/`

---

## 0. TL;DR

The app is now deployable and defensible:

- **Auth0 login** (OAuth2 Authorization Code + PKCE in the browser). The backend validates the JWT's signature, issuer, audience and expiry.
- **Roles:** the ledger, audit log and settlement endpoints require `admin`.
- **Pseudonymous identity:** other bidders see `u-3f9a1c2b7d`, never your email or Auth0 id.
- **Abuse protection:** a token-bucket rate limit (HTTP 429 + `Retry-After`; mutations cost 5×), a 64 KB body cap, and GraphQL **depth** and **complexity** limits.
- **Scales horizontally:** live bid updates fan out across replicas through **PostgreSQL LISTEN/NOTIFY**. Settlement and seeding are safe to run on several replicas.
- **Ships:** multi-stage, non-root, layered Docker images; a one-command full stack; CI that tests, validates infrastructure and publishes images to GHCR; hardened Kubernetes manifests; OpenTofu for Linode LKE + managed PostgreSQL + Cloudflare DNS.

**Demo mode still needs nothing:** without Auth0 settings, the app runs exactly as before, with a built-in demo user.

---

## 1. How to run on Windows

### 1.1 Whole stack in containers (one command)

```powershell
docker compose --profile app up --build
# frontend http://localhost:3000 · API http://localhost:8080/graphql · GraphiQL http://localhost:8080/graphiql
docker compose --profile app down        # add -v to also wipe the database
```

### 1.2 Auth0 setup (optional, about 15 minutes, free tier)

1. Sign up at <https://auth0.com>, which creates a tenant such as `your-tenant.us.auth0.com`.
2. **Applications → APIs → Create API.** Name `QuipMarket API`, Identifier `https://api.quipmarket.dev` (this is the **audience**), signing algorithm RS256.
3. **Applications → Applications → Create → Single Page Application.** Then set:
   - Allowed Callback URLs, Allowed Logout URLs and Allowed Web Origins: `http://localhost:3000`
   - Advanced → Grant Types: make sure **Refresh Token** is enabled. Then **APIs → your API → Settings → Allow Offline Access: on**.
   - **Applications → your SPA → Settings → Refresh Token Rotation: on.**
4. **Roles:** go to User Management → Roles → create `admin` and assign it to your user.
5. **Put roles into the token:** Actions → Library → Build Custom → trigger *Login / Post Login*, then Deploy and add it to the Login flow:
   ```js
   exports.onExecutePostLogin = async (event, api) => {
     const roles = event.authorization?.roles ?? [];
     api.accessToken.setCustomClaim('https://quipmarket.dev/roles', roles);
   };
   ```
6. Run with Auth0:
   ```powershell
   # backend
   $env:AUTH_MODE = "auth0"
   $env:AUTH0_ISSUER = "https://your-tenant.us.auth0.com/"
   $env:AUTH0_AUDIENCE = "https://api.quipmarket.dev"
   cd backend; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
   # frontend (new window)
   $env:NUXT_PUBLIC_GRAPHQL_URL = "http://localhost:8080/graphql"
   $env:NUXT_PUBLIC_AUTH0_DOMAIN = "your-tenant.us.auth0.com"
   $env:NUXT_PUBLIC_AUTH0_CLIENT_ID = "<SPA client id>"
   $env:NUXT_PUBLIC_AUTH0_AUDIENCE = "https://api.quipmarket.dev"
   cd frontend; npm run dev
   ```
   Click **Log in** → Auth0 → back to the app, logged in. My deals works, and Ledger works if you have `admin`.

> **Honesty note:** this sandbox cannot reach Auth0. JWT validation is proven with **real RS256-signed tokens** in `Auth0ModeIT`, which runs the production issuer/audience/expiry validators with a locally generated key. In Chromium I checked the guest experience with Auth0 unreachable: "Log in" button, "Log in to bid", Ledger gated, no page errors. The full login round-trip has to be run once on your machine with the steps above.

### 1.3 CI

The first push or PR runs `.github/workflows/ci.yml` automatically: open the **Actions** tab on GitHub. On `main`, images are published to `ghcr.io/cathy0326/quipmarket-backend` and `-frontend` (see **Packages** on your profile).

### 1.4 Kubernetes + OpenTofu (optional, costs money)

```powershell
cd infra
$env:TF_VAR_linode_token = "..."; $env:TF_VAR_cloudflare_api_token = "..."
copy terraform.tfvars.example terraform.tfvars   # fill in zone id + domain
tofu init; tofu apply                            # LKE cluster + managed PostgreSQL (~$50-70/month; destroy after the demo!)
tofu output -raw kubeconfig | base64 -d > $HOME\.kube\quipmarket.yaml
# install ingress-nginx + cert-manager (Helm), create the backend-secrets Secret (k8s/base/secret.example.yaml.txt), then:
kubectl apply -k ..\k8s\base
tofu destroy                                     # when done
```
> The Linode Postgres `allow_list` must include your LKE node IPs, otherwise the backend can't connect.

---

## 2. Big picture

### 2.1 Request path in production

```
Browser ──HTTPS──► Cloudflare (DNS, TLS, DDoS) ──► ingress-nginx (LKE) ──┬─► frontend pods (Nuxt SSR) ×2
   │  Auth0 login (PKCE)                                                  │        │ SSR: http://backend:8080 (cluster-internal)
   │  Bearer <access token>                                               └─► backend pods (Spring) ×2
   ▼                                                                               │
Auth0 ◄── JWKS (public keys, cached) ──────────────────────────────────────────────┤
                                                                                   ▼
                                                              Linode Managed PostgreSQL 16
                                                     (data · ledger · audit · LISTEN/NOTIFY fan-out)
```

### 2.2 What happens to one request inside the backend

```
POST /graphql
  1. Spring Security    Bearer token? → verify signature (JWKS), iss, aud, exp → 401 if bad
  2. RateLimitFilter    body ≤ 64 KB? → bucket[user|ip].tryConsume(1 or 5) → 429 + Retry-After
  3. CurrentUser        sub → pseudonym "u-…", roles claim → GraphQL context
  4. graphql-java       depth ≤ 10? complexity ≤ 400? → else reject before executing anything
  5. resolver           e.g. trialBalance → CurrentUser.requireAdmin() → FORBIDDEN if not admin
```

### 2.3 Live updates across replicas

```
replica A: placeBid tx ── SELECT pg_notify('auction_changes', 'au-2001') ── COMMIT
                                                   │  (delivered only on COMMIT)
                     PostgreSQL ───────────────────┼────────────────────┐
                                                   ▼                    ▼
           replica A listener thread       replica B listener thread
                   │                                  │
           A's WebSocket subscribers          B's WebSocket subscribers
```

### 2.4 Token bucket (rate limiting)

```
capacity 60 tokens, refill 10/s, query = 1 token, mutation = 5 tokens
   tokens = min(capacity, tokens + elapsed × rate)     ← lazy refill on each request, O(1)
   tokens ≥ cost ? tokens -= cost : 429, Retry-After = ceil((cost - tokens) / rate)
```
Measured in the container: a burst of 150 mutations from one user → 13 accepted, 137 rejected with 429.

### 2.5 CI pipeline

```
push / PR ─┬─ frontend:        npm ci → typecheck → 57 unit tests → build
           ├─ backend:         mvnw verify (41 unit + 41 Testcontainers integration tests)
           ├─ infrastructure:  kubeconform (k8s schemas) + tofu fmt/validate
           └─ kubernetes:      build both images → kind cluster → kubectl apply -k k8s/overlays/ci → smoke-test.sh
                         │
                  frontend + backend green
                         ▼
           images (matrix backend/frontend): buildx with GHA cache → push to GHCR on main only
```

---

## 3. File map

| File | Purpose | Read first? |
|---|---|---|
| `backend/.../shared/SecurityConfig.java` | Two auth modes, JWT validators (issuer + audience) | ⭐⭐⭐ |
| `backend/.../shared/CurrentUser.java` | Identity → pseudonym + roles; WebSocket token validation | ⭐⭐⭐ |
| `backend/.../shared/TokenBucket.java` | The rate-limit algorithm (interview classic) | ⭐⭐⭐ |
| `backend/.../shared/RateLimitFilter.java` | Per-caller buckets, body cap, 429 + Retry-After | ⭐⭐ |
| `backend/.../shared/ApiProtectionConfig.java` | Depth/complexity limits, filter ordering after Security | ⭐⭐ |
| `backend/.../auction/internal/AuctionChangeNotifier.java` | LISTEN/NOTIFY fan-out with reconnect + backoff | ⭐⭐ |
| `backend/src/test/.../shared/Auth0ModeIT.java` | Real signed JWTs: valid, forged, expired, wrong audience/issuer | ⭐⭐⭐ |
| `backend/src/test/.../shared/AdminAndLimitsIT.java` | Roles, `me`, depth limit, LISTEN/NOTIFY round trip | ⭐⭐ |
| `frontend/app/composables/useAuth.ts`, `plugins/auth0.client.ts` | SPA login, token for API calls, `me` lookup | ⭐⭐ |
| `backend/Dockerfile`, `frontend/Dockerfile` | Multi-stage, layered, non-root images | ⭐⭐ |
| `compose.yaml` | DB only, or the full stack with `--profile app` | ⭐ |
| `.github/workflows/ci.yml` | CI/CD | ⭐⭐ |
| `k8s/base/*.yaml` | Deployments, probes, PDB, ingress, least-privilege config | ⭐⭐ |
| `k8s/overlays/ci/` | The same manifests deployed to a throwaway kind cluster in CI, plus in-cluster PostgreSQL | ⭐⭐ |
| `infra/*.tf` | OpenTofu: LKE, managed PostgreSQL, Cloudflare DNS | ⭐ |

---

## 4. Key concepts, explained small

### 4.1 Authentication vs authorization
- **Authentication** = who are you? → Auth0 issues a signed JWT; we verify it.
- **Authorization** = what may you do? → our code checks `roles` (e.g. `requireAdmin`).
- Hiding the Ledger link in the UI is **UX**; the server-side check is **security**. The test calls the API directly to prove it.

### 4.2 What "validating a JWT" actually means
1. **Signature:** signed with Auth0's private key, verified with the public key from `/.well-known/jwks.json`. A forged token fails (tested with a second key pair).
2. **`exp` / `nbf`:** not expired, not used too early.
3. **`iss`:** issued by *our* tenant.
4. **`aud`:** issued *for this API*. Without this check, a token for another API in the same tenant would be accepted. This is a common real-world bug.

### 4.3 Why no CSRF protection?
CSRF works because browsers automatically attach **cookies** to cross-site requests. This API uses a bearer token in a header, which the browser never attaches on its own. With no session cookie, there's nothing to forge. We still restrict CORS origins.

### 4.4 Rate limiting a GraphQL API needs three layers
- **Requests per caller** (token bucket) → stops floods.
- **Cost per request** (mutations 5×) → writes and payment calls are expensive.
- **Query shape** (depth 10, complexity 400) → stops one "small" request that asks for millions of nested objects. The schema has a cycle (`auction → equipment → activeAuction → …`); the test proves the deep query is rejected.

### 4.5 Making background work safe on N replicas

| Work | Why it's safe with several replicas |
|---|---|
| Bids | Row lock per auction (`FOR UPDATE`) |
| Settlement job | `UPDATE … WHERE settled_at IS NULL` claim: one winner |
| Saga steps | Deterministic provider idempotency keys + forward-only states |
| Demo reseed | `pg_try_advisory_xact_lock`: one seeder |
| Subscriptions | LISTEN/NOTIFY to every replica |
| Rate limits | Per replica (effective limit ≤ N×) — acceptable for abuse protection |

### 4.6 Container hardening checklist (all applied)
Multi-stage (no JDK, Maven or npm in the runtime image) · **non-root** user (`uid 10001`, verified with `id` in the container) · read-only root filesystem with an `emptyDir` for `/tmp` · all Linux capabilities dropped · seccomp `RuntimeDefault` · heap sized from the container memory limit (`MaxRAMPercentage`) · Spring Boot **layers**, so a code-only change re-pushes the small application layer (a few hundred KB) instead of every dependency.

### 4.7 Kubernetes probes
- **startup:** gives Flyway up to 2 minutes on first boot before liveness starts judging.
- **readiness:** "send me traffic". During a rolling update, pods receive requests only once ready.
- **liveness:** "restart me if stuck".
- `maxUnavailable: 0` + a PodDisruptionBudget mean deploys and node drains never take the API fully down.

---

## 5. Real bugs found while building

| # | What happened | Root cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | Auth0 tests: *two JwtDecoder beans* | `@ConditionalOnMissingBean` on a normal `@Configuration` depends on registration order (reliable only in auto-configuration) | Removed it; test decoder is `@Primary` | Know where Spring conditions are reliable |
| 2 | Demo seeding not transactional at startup | `run()` called `reseedIfIdle()` on `this` → **self-invocation bypasses the `@Transactional` proxy** | Explicit `TransactionTemplate` | One of the most common Spring interview questions, found for real |
| 3 | Anonymous pages hung in auth0 mode when Auth0 was unreachable | `createAuth0Client()` performs a silent session check before resolving; every API call awaited it | `new Auth0Client()`: local cache read; Auth0 only for real login/refresh | Never block public data on an identity provider |
| 4 | First k8s draft gave the frontend pod the backend's Stripe secret | One shared Secret for all workloads | Separate ConfigMaps; Secret mounted only into the backend | Least privilege, per workload |
| 5 | Image builds failed: `SELF_SIGNED_CERT_IN_CHAIN`, then PKIX | TLS-intercepting proxy; `keytool` imports only the FIRST cert of a bundle | Optional BuildKit secret `extra_ca`; split the bundle and import each cert (build stage only) | Corporate-proxy builds, without baking certs into images |
| 6 | My auth0 browser test "failed" with CORS | I ran the test frontend on port 3001, which isn't an allowed origin | Test on the allowed origin | CORS doing its job looks like a bug from the outside |

---

## 6. Evidence

| Check | Result |
|---|---|
| `Auth0ModeIT` (6) | Anonymous browse OK; token → pseudonym; demo headers ignored; **forged, expired, wrong-audience and wrong-issuer tokens → 401**; admin from claim; GraphiQL disabled |
| `AdminAndLimitsIT` (4) | FORBIDDEN without admin; `me`; depth-limit rejection; bid notification travels through PostgreSQL LISTEN/NOTIFY |
| `TokenBucketTest` (3), `RateLimitFilterTest` (3) | Burst + refill, capacity cap, cost; per-caller buckets, 429 + Retry-After, 413 for huge bodies |
| Containers | Backend + frontend images built; compose stack healthy; SSR through the internal URL; runs as uid 10001; all 9 pages clean; 150-request burst → 13 accepted, 137 × 429 |
| Kubernetes | 9 resources pass `kubernetes-validate --strict` for 1.33 |
| OpenTofu | HCL parses; `tofu validate` runs in CI (not available in this sandbox) |

---

## 7. Self-test

<details><summary>Q1. Your API accepts any valid Auth0 token from your tenant. What's missing?</summary>

The audience check. A token issued for a different API (or app) in the same tenant would be accepted. Validate `aud` = this API's identifier.
</details>

<details><summary>Q2. Why does the rate limiter run AFTER Spring Security?</summary>

So it can key the bucket on the authenticated subject instead of the IP. Many users behind one corporate NAT share an IP; one user can rotate IPs.
</details>

<details><summary>Q3. A single GraphQL request takes down the database. Which protection was missing?</summary>

Depth/complexity limits. Rate limiting counts requests, not how much work each request asks for.
</details>

<details><summary>Q4. Why is `@Transactional` ignored when `run()` calls `this.reseedIfIdle()`?</summary>

Spring implements @Transactional with a proxy around the bean. Calling a method on `this` skips the proxy, so no transaction starts.
</details>

<details><summary>Q5. Why does pg_notify inside the transaction guarantee subscribers never see a rolled-back bid?</summary>

PostgreSQL queues the notification and delivers it only when the transaction commits. On rollback it is discarded.
</details>

<details><summary>Q6. Why a startup probe in addition to liveness?</summary>

The first boot runs migrations and can be slow. Without a startup probe, liveness might kill the pod before it ever finishes starting (crash loop).
</details>

<details><summary>Q7. Why are NUXT_PUBLIC_AUTH0_* values in a ConfigMap, not a Secret?</summary>

They are public by design: the browser receives them. Secrets are for things that grant power (Stripe secret key, DB password), and those are mounted only into the backend.
</details>

---

## 8. Interview Q&A

**Q (MassQuip): How is the app deployed?**
> "Two Docker images built in CI: multi-stage, non-root, with Spring Boot layers. On main, CI pushes them to GHCR. They run on Kubernetes: two replicas each with startup, readiness and liveness probes, a PodDisruptionBudget, read-only filesystems, and least-privilege config. The cluster, managed Postgres and Cloudflare DNS are defined in OpenTofu. Auth is Auth0, with JWTs validated by the Spring resource server, including the audience."

**Q (Stripe): How do you protect a public API from abuse?**
> "Three layers: a token-bucket rate limit per authenticated caller that returns 429 with Retry-After, where mutations cost more than reads; a body size cap; and, because it's GraphQL, depth and complexity limits so one request can't ask for unbounded nested data. All three are covered by tests, and in a container a burst of 150 mutations let 13 through."

**Q (SIG): How did you scale real-time updates beyond one server?**
> "Each bid transaction calls pg_notify, which Postgres delivers only on commit. Every replica keeps one LISTEN connection and forwards events to its WebSocket subscribers. It needs no extra infrastructure, and it never announces a bid that rolled back. Past that scale I'd move to a log-based broker like Kafka for durability and replay."

---

## 9. Known limitations

| Limitation | Production answer |
|---|---|
| Rate limits are per replica | Redis-backed buckets, or limits at the API gateway (Cloudflare, Kong) |
| No observability stack | Micrometer → Prometheus/Grafana, OpenTelemetry tracing, structured JSON logs |
| Secrets created by hand in k8s | External Secrets Operator / Sealed Secrets |
| No automated deploy step | Argo CD (GitOps) watching image tags, or a `kubectl apply -k` job with environment approvals |
| Auth0 tokens in localStorage | Backend-for-frontend (BFF) with HttpOnly cookies if the threat model requires it |
| Single-region DB | Read replicas / HA plan on the managed database |

---

## 10. How to study this phase

**Principles:**
1. **Generation:** Implement `TokenBucket.tryConsume` yourself before reading it (it's a LeetCode-design-style question), then run `TokenBucketTest`.
2. **Retrieval:** Draw diagram 2.2 (the five stages of a request) from memory.
3. **Interleaving:** Pair this with NeetCode *Design* problems (e.g. "Design Hit Counter", "Time Based Key-Value Store"). Same thinking: state + time.

**Concrete drills:**
- 🔁 **Break-it:** Delete the audience validator in `SecurityConfig.validators` → `Auth0ModeIT.forgedExpiredOrForeignTokensAreRejectedWith401` fails on the wrong-audience case.
- 🔁 **Break-it:** Change `MaxQueryDepthInstrumentation(10)` to `50` → `absurdlyDeepQueriesAreRejectedBeforeExecution` fails.
- 🔁 **Hands-on:** `docker compose --profile app up --build`, then run `docker compose exec backend id` → `uid=10001`.
- 🔁 **Hands-on:** Open the GitHub **Actions** tab after your next push and read each job's log.

---

## 11. PR text to paste

**Title:**
```
Phase 4: Auth0, rate limiting, query limits, LISTEN/NOTIFY, Docker, CI, Kubernetes, OpenTofu
```

**Body:**
```markdown
## Summary
- Auth: Spring Security in two modes (demo header / Auth0 JWT resource server with issuer + audience validation); pseudonymous public user ids; admin role for ledger, audit and settlement; `me` query. Frontend Auth0 SPA login (PKCE) with non-blocking client.
- API protection: token-bucket rate limit per caller (429 + Retry-After, mutations cost 5x, 64 KB body cap); GraphQL max depth 10 and complexity 400.
- Multi-replica: auction events via PostgreSQL LISTEN/NOTIFY (delivered on commit); demo seeding guarded by an advisory lock; fixed a self-invocation @Transactional bug.
- Delivery: multi-stage non-root layered Dockerfiles (optional build-time extra CA for corporate proxies); full-stack compose profile; GitHub Actions CI (frontend, backend with Testcontainers, kubeconform + tofu validate, image build/push to GHCR on main).
- Kubernetes: deployments with startup/readiness/liveness probes, PDB, read-only root fs, dropped capabilities, per-workload config; ingress with TLS and WebSocket timeouts.
- OpenTofu: Linode LKE (autoscaling pool), managed PostgreSQL 16, Cloudflare DNS.

## Testing
- Backend: 41 unit + 41 integration tests (incl. RS256-signed JWTs: valid/forged/expired/wrong audience/wrong issuer)
- Frontend: 57 unit tests, typecheck clean
- Images built and full stack verified via compose (SSR via internal URL, non-root, rate limit 429s)
- Kubernetes manifests pass strict schema validation for 1.33

## Docs
- docs/handover/phase-4.md, docs/CAPSTONE.md
```
