# QuipMarket mobile (Kotlin Multiplatform)

One Kotlin module, `shared`, holds what both phone apps need: the money rules, rental pricing and a typed client for the backend's GraphQL API. Android uses it as a library; iOS uses it as `Shared.xcframework`. The Android app (`androidApp`, Jetpack Compose) lists rental equipment from the API and prices any rental length on the device.

```
                        backend/src/main/resources/graphql/schema.graphqls
                                   │ (Apollo Kotlin generates typed queries at build time)
contracts/money-rules.json         ▼
   │ (Gradle generates      ┌──────────────── shared (commonMain) ─────────────────┐
   │  Kotlin test cases)    │ Money.kt         platformFeeCents, formatCents       │
   └──────────────────────► │ RentalPricing.kt cheapest day/week/month mix (DP)    │
                            │ QuipMarketApi.kt rentalListings() via Apollo         │
                            └───────┬──────────────────────┬───────────────────────┘
                                    │ Android library       │ Kotlin/Native → Shared.xcframework
                              androidApp (Compose)      iOS app (SwiftUI, imports Shared)
```

## Why the prices always match

The same table of cases, [`contracts/money-rules.json`](../contracts/money-rules.json), is checked by:

| Client | Test | Runs on |
|---|---|---|
| Backend (Java) | `MoneyRulesContractTest` | JVM, in `./mvnw verify` |
| Web (TypeScript) | `moneyRulesContract.test.ts` | Node, in `npm test` |
| Mobile (Kotlin) | `MoneyRulesContractTest` | JVM, Android **and the iOS simulator** |

Change a rule in the contract file first; CI then shows every client that still disagrees. The mobile workflow also runs when the backend's GraphQL schema changes, so a renamed or removed field breaks the mobile build in the same pull request instead of in the app store.

## Run it

**Android app** (Android Studio, or the command line with an Android SDK):

```bash
docker compose --profile app up --build            # from the repository root: backend on localhost:8080
cd mobile
./gradlew :androidApp:installDebug                 # emulator running; the app calls http://10.0.2.2:8080/graphql
./gradlew :androidApp:assembleDebug -PgraphqlUrl=https://api.staging.quipmarket.example.com/graphql
```

Windows: use `gradlew.bat` instead of `./gradlew`. CI attaches a ready-to-install debug APK to every run (artifact `quipmarket-android-debug-apk`).

**Tests:**

```bash
./gradlew :shared:jvmTest                          # fastest: no Android SDK or Mac needed
./gradlew :shared:testDebugUnitTest                # Android
./gradlew :shared:iosSimulatorArm64Test            # macOS with Xcode
```

**iOS:** on a Mac, `./gradlew :shared:assembleSharedReleaseXCFramework` produces `shared/build/XCFrameworks/release/Shared.xcframework` (CI attaches it too). Drag it into an Xcode project, then:

```swift
import Shared

let quote = RentalPricing.shared.quote(rentalDays: 31, rates: RentalRates(dailyCents: 110_000, weeklyCents: 320_000, monthlyCents: 800_000))
print(quote.describe())   // "1 month + 1 week"
```

## Decisions

| Decision | Reason |
|---|---|
| Share logic and networking, keep UI native (Compose / SwiftUI) | Each platform keeps its native look and accessibility; the code that must never differ (money, API contract) exists once |
| Apollo Kotlin reading the backend's schema file directly | One source of truth: no copied schema to drift out of date |
| Money in `Long` cents, no floating point | Same as the backend and the web app; `0.1 + 0.2` problems cannot occur |
| Plain `jvm()` target next to Android and iOS | Contributors without an Android SDK or a Mac can still run the shared tests in seconds |
| Cleartext HTTP allowed only to `10.0.2.2` | The emulator's alias for the developer's machine; everything else must be HTTPS |
| Prices computed on the device | Instant feedback while dragging the slider and no request per change; the contract tests keep it equal to the server, which stays the authority when booking |
