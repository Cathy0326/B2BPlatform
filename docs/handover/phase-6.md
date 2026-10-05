# Phase 6 Handover: tests that prove the tests work

Phase 6 (Q2b in the quality plan) adds three kinds of testing that answer the question coverage cannot: *would these tests catch a real bug?*

| Technique | Tool | Result |
|---|---|---|
| Property-based testing | jqwik (backend), fast-check (frontend) | 20 properties over the auction engine, rental pricing, loans, fee, ledger and money formatting |
| Mutation testing | PIT | 99.2% of 131 planted bugs caught; CI fails below 95% |
| Browser + accessibility testing | Playwright, axe-core | 40 tests: 4 buyer journeys and WCAG 2.1 A/AA on 8 pages, light/dark, desktop/phone; 0 violations |

All of it runs in CI and appears in the quality report on every run's *Summary* tab, plus a mutation-score badge in the README.

---

## 1. Big picture

```
                    "Do the tests catch bugs?"
                              │
   ┌──────────────────────────┼───────────────────────────┐
   ▼                          ▼                           ▼
 PROPERTY-BASED            MUTATION                   BROWSER + AXE
 state a rule, generate    plant a bug, run the       click through the real
 hundreds of inputs,       tests, see if any          build like a buyer;
 shrink failures           fails                      scan every page for WCAG
   │                          │                           │
   ├ found: tie-break model   ├ found: 8 untested         ├ found: low contrast,
   │ used time, not arrival   │ boundaries + a lucky      │ bad ARIA, colour-only
   │                          │ random hit                │ links, unreachable tables
   ▼                          ▼                           ▼
 jqwik + fast-check        PIT 92.4% → 99.2%          axe 0 violations
 in unit suites            gate 95% in CI             CI job, traces on failure
```

## 2. Files

| Path | What |
|---|---|
| `backend/src/test/java/**/*Properties.java` | jqwik properties: `AuctionEngineProperties`, `RentalPricingProperties`, `LoanCalculatorProperties`, `PlatformFeeProperties` |
| `backend/src/test/java/com/quipmarket/ledger/LedgerIT.java` | `randomPostingSequencesKeepTheBooksBalanced`: 200 seeded random entries against PostgreSQL |
| `backend/src/test/resources/junit-platform.properties` | jqwik: report only failures, database under `target/` |
| `backend/pom.xml` | jqwik dependency; `pitest-maven` with target classes and `mutationThreshold` 95 |
| `backend/src/test/java/.../AuctionEngineTest.java` | `Boundaries` and soft-close edge tests added from PIT survivors |
| `frontend/tests/properties.test.ts` | fast-check properties |
| `frontend/e2e/journeys.spec.ts`, `accessibility.spec.ts` | Playwright journeys and axe checks |
| `frontend/playwright.config.ts` | Production build, demo-data mode, desktop + Pixel 7, no retries |
| `contracts/money-rules.json` | New tie case: a month costing the same as four weeks picks the weeks |
| `.github/workflows/ci.yml` | PIT step in the backend job; new `browser` job |
| `scripts/quality-report.py` | Browser suite row, mutation section, `mutation.json` badge |

## 3. How to run

```bash
cd backend  && ./mvnw test -Dtest='*Properties'                          # jqwik properties only
cd backend  && ./mvnw test-compile org.pitest:pitest-maven:mutationCoverage  # report: target/pit-reports/index.html
cd frontend && npx vitest run tests/properties.test.ts                    # fast-check properties
cd frontend && npm run build && npm run test:e2e                          # report: reports/playwright/index.html
```

Windows PowerShell: the same commands, with `.\mvnw.cmd` instead of `./mvnw`. On a failure, jqwik and fast-check print the seed and the shrunk counterexample. Paste the seed into `@Property(seed = "...")` or `fc.assert(..., { seed })` to replay it exactly.

## 4. What was verified

| Claim | Evidence |
|---|---|
| Properties can fail | A planted tie-break bug (`>` to `>=`) is caught by jqwik (shrunk to two bids) and by fast-check (6 of 6 runs after the generator fix) |
| The mutation score is stable | Two consecutive PIT runs: 130/131, same survivor |
| Accessibility fixes are real | Contrast ratios recomputed for every changed colour pair; axe reports 0 violations in both themes and both viewports |
| Browser tests are not flaky | Journeys repeated 3×: 24/24; full suite 40/40, checked by exit code |
| Nothing else broke | Full `mvnw verify` (coverage gates, SpotBugs), 188 Vitest tests, lint, typecheck all green; quality report 455/455 |

**One mistake worth remembering:** an early browser run printed "36 passed", and I read only the last lines of the output. Four phone-viewport failures were hidden above them. Rerunning with the exit code checked found them. A summary line is not a pass; the exit code is.

## 5. Interview story (STAR)

> **Situation:** The project already had 94% line coverage, but coverage only shows which code ran, not whether a test would notice it being wrong.
> **Task:** Measure and raise the bug-catching power of the suite for the code that moves money.
> **Action:** Added property-based tests that state rules (the auction winner pays between the runner-up's max and their own) and check them on thousands of random bidding wars. Then ran mutation testing, which planted 131 small bugs; the first score was 92%, and every survivor pointed at an untested boundary, such as a bid of exactly the minimum or a bid exactly two minutes before the close. Finally added Playwright journeys and axe accessibility checks, which found four real WCAG failures, one only on a phone-sized screen.
> **Result:** 99% mutation score gated in CI, 0 accessibility violations, and the one remaining mutant is documented as provably equivalent.

Numbers to remember: **92% → 99% mutation score · 131 mutants · 20 properties · 40 browser tests · 0 WCAG violations · 455 tests**.

## 6. Self-test (retrieval practice)

Answer from memory, then check section 8 of `docs/QUALITY.md`:

1. What does mutation testing measure that line coverage does not? Give one surviving mutant from this project and the test that killed it.
2. Why is `principalPart > balance` → `>=` impossible to kill, and why report it instead of excluding it?
3. The fast-check auction property missed the planted bug at first. Why, and what two changes fixed it?
4. What does "shrinking" mean, and what did the shrunk counterexample reveal about time priority?
5. Why run the accessibility checks on a phone viewport as well as desktop?
6. Why are Playwright retries set to 0?
