# Crossdeck — Android SDK

The Crossdeck SDK for Android (Kotlin, Java-friendly).

> ⚠️ **PRE-RELEASE — not yet published.** This SDK is source-complete in
> the monorepo but has **not shipped to Maven Central**; the Gradle
> coordinates below will not resolve yet. The first public release will
> be **v2.0.0**, born conformant to Event Envelope v1 (the server's
> wire-format floor for Android is 2.0.0 — earlier builds are never
> accepted). Until then, treat everything below as a preview of the
> shipped surface.

> **Status: v1.4.1 — full bank-grade parity.** Modeled line-for-line
> on the Web, Node, React Native, and Swift v1.2.0 SDKs. v1.2.0 adds
> **auto-tracking** (sessions, screen views, tap autocapture),
> **performance vitals** (cold launch, ANR detection, frame jank),
> **ProcessLifecycleOwner** foreground/background, **network-edge
> flush**, **crash-on-relaunch** persistence, Google Play Billing
> helpers, deep-link + push attribution helpers, and Mixpanel-style
> `group()` API. Same cross-platform event vocabulary
> (`session.started`, `page.viewed`, `element.clicked`) as Swift +
> Web SDKs — one dashboard query returns all platforms. Two runtime
> dependencies (`kotlinx-coroutines-android`,
> `androidx.lifecycle:lifecycle-process`).

## Three pillars

| Pillar | What it does | Why it matters |
| ------ | ------------ | -------------- |
| **Events** | Durable, deduplicated, batched event ingest. Survives crashes, offline, and process-kill. | Your funnels, cohorts, and revenue analytics rest on this never losing or double-counting an event. |
| **Errors** | Uncaught `Throwable` capture (chains into Crashlytics / Sentry), manual `captureError(...)`, stack normalisation, breadcrumbs, `beforeSend` hook. | When something breaks in prod, you get the actual stack + the user's last 50 actions, not "NullPointerException". |
| **Entitlements** | Synchronous read of "is this customer entitled to feature X?" with on-device cache and async refresh. | Paywall gates without a network round-trip. |

## Install

### Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.crossdeck:crossdeck:1.+")
}
```

### Gradle (Groovy DSL)

```groovy
// app/build.gradle
dependencies {
    implementation 'com.crossdeck:crossdeck:1.+'
}
```

Requires `minSdk = 21` (Android 5.0+), Java 17, Kotlin 1.9+.

## Quickstart

```kotlin
import com.crossdeck.Crossdeck
import com.crossdeck.CrossdeckOptions
import com.crossdeck.Environment

class MyApplication : Application() {
    lateinit var cd: Crossdeck

    override fun onCreate() {
        super.onCreate()
        cd = Crossdeck.start(
            this,
            CrossdeckOptions(
                appId = "app_android_acme01",
                publicKey = "cd_pub_live_...",
                environment = Environment.PRODUCTION,
            ),
        )
    }
}

// Track an event
cd.track("paywall_seen", mapOf("variant" to "annual"))

// Identify a customer
cd.identify(userId = "user_847", email = "wes@example.com", traits = mapOf("plan" to "pro"))

// Synchronous paywall gate (safe from any thread, never blocks on network)
if (cd.isEntitled("pro_features")) {
    showProUI()
} else {
    showPaywall()
}

// Manual error capture
try {
    riskyOperation()
} catch (e: Throwable) {
    cd.captureError(e)
}

// Drain before app shutdown (suspend — call from a coroutine)
runBlocking { cd.flush() }
```

## Bank-grade contracts

These are the data-integrity guarantees the SDK ships with, and the
patterns it enforces. They are the SAME contracts that govern the
Web, Node, React Native, and Swift SDKs.

### Events

- **Never lost.** Buffered events are persisted to
  `SharedPreferences` on every enqueue. The in-flight batch is held
  in a dedicated `pendingBatch` slot so a crash mid-HTTP-request
  leaves the batch intact on disk for the next launch.
- **Never double-inserted.** Each batch gets a stable
  `Idempotency-Key` reused across retries. Server-side dedup
  collapses retries to a single insert.
- **4xx hard stop.** A permanent 4xx (auth, payload broken) is
  routed to the `onPermanentFailure` callback and dropped — it
  will never block newer events behind a dead batch. The 408
  (Request Timeout) and 429 (Rate Limit) carve-outs stay retryable.
- **`Retry-After` honoured.** Server is authoritative on its own
  rate budget. Clamped at 24h as a sanity cap.
- **Auto-flush on app background.** `ActivityLifecycleCallbacks`
  ships pending events when any activity pauses, so the few
  seconds Android gives before suspension always include a drain.

### Errors

- **`beforeSend` hook.** Final filter before an error event leaves
  the device. Return `null` to drop. Replaceable at runtime via
  `setErrorBeforeSend(...)`.
- **Self-request skip.** The SDK's own HTTP errors against its own
  ingest endpoint are skipped — no feedback loops.
- **Chains into prior `UncaughtExceptionHandler`.** When you turn
  on `captureUncaughtExceptions`, Crossdeck captures the handler
  that was registered before us (Crashlytics, Sentry, Bugsnag) and
  forwards every uncaught throw to it AFTER our snapshot. Both
  reporters keep working.
- **Breadcrumbs.** Ring buffer of the user's last 50 actions
  attached to every captured error. Scrubbed for PII.

### Entitlements

- **Customer-scoped.** The cache key is `(developerUserId, entitlements)`.
  A read for a different customer returns `null` — never leaks a
  prior user's entitlements after identify.
- **Synchronous read.** `isEntitled(...)` returns instantly from
  cache. Paywall gates do not block on network.
- **Outage = preserve.** A Crossdeck outage MUST NOT fail a paying
  customer down to free. `markRefreshFailed` records the failure
  WITHOUT invalidating the cache — last-known-good wins until the
  next successful refresh.

### Purchases

- **Single backend contract.** Auto-track (`handleBillingResult`)
  and manual (`syncPurchases`) share `POST /purchases/sync` —
  one wire shape, no drift between paths.
- **Canonical rail token.** Android emits `rail = "google"` on the
  wire — matches `PaymentRail` in `backend/src/lib/types.ts`. The
  endpoint currently returns 501 `google_not_supported`; that
  flows through as a typed failure unchanged when the Play
  Developer API reconciliation worker lights up.
- **Typed errors — no silent swallow.** A failed `/purchases/sync`
  surfaces on THREE independent channels:
  1. `purchase.sync_failed` analytics event with `errorType`,
     `errorCode`, `statusCode`, `requestId`, `productId` —
     visible in dashboards.
  2. Optional `onSyncResult: (BillingPurchase, Result<Unit>) ->
     Unit` callback on `handleBillingResult` — `Result.failure`
     carries the [CrossdeckError] with the typed envelope.
  3. `options.debugLogger` with the full typed structure
     (`error_type`, `error_code`, `status_code`, `request_id`,
     `product_id`) — visible in dev-mode logs.
- **Funnel event before sync.** `purchase.completed` /
  `purchase.refunded` fires immediately on a successful billing
  result; the dashboard row appears even if the backend sync is
  still in flight.

### Identity

- **`anonymousId` persists across launches** until `reset()`.
- **`reset()` regenerates `anonymousId`** so the next anonymous
  session is not linked to the prior identified user.
- **Per-user entitlement cache isolation (v1.4.1).** Bank-grade
  three-layer contract — a freshly identified user must never
  observe the prior user's entitlements via any sync read path:
  - **(a) Physical key separation.** Each user's cache lives under
    `crossdeck:entitlements:<sha256(userId)>` — distinct from
    every other user on the device. A botched in-memory wipe
    cannot cross-read because the storage keys differ.
  - **(b) Unconditional in-memory wipe on identify.** Every
    `identify(userId)` flips the active suffix, nilling the
    snapshot before returning. Even same-id re-identify wipes;
    a tiny redundant rebuild is cheaper than a leak.
  - **(c) Logout-grade `reset()` wipe.** `reset()` reads the
    persisted index and removes every per-user slot on the
    device — a shared-device logout can never leave another
    user's entitlements readable.

### Privacy

- **PII scrubber on by default.** `<email>` and `<card>` tokens
  replace anything that looks like an email or payment card. Walks
  nested maps and lists recursively, depth-guarded.
- **Default-grant consent.** Both analytics and errors on by
  default — matches the platform-wide contract. Wire opt-out via
  `setConsent(ConsentState(analytics = false))` for cookie-banner
  / EU AGE-gate flows.

### `CrossdeckContracts` — typed access to the bundled contract registry

The SDK ships the full bank-grade contract registry as an indexed
JAR resource. Query it at runtime:

```kotlin
import com.crossdeck.CrossdeckContracts
import com.crossdeck.ContractPillar
import com.crossdeck.ContractStatus

for (contract in CrossdeckContracts.all()) {
    Log.i("crossdeck", "${contract.id} (${contract.pillar.wire})")
}

val isolation = CrossdeckContracts.byId("per-user-cache-isolation")
    ?: error("entitlement isolation contract missing")
check(isolation.status == ContractStatus.ENFORCED)

CrossdeckContracts.byPillar(ContractPillar.ENTITLEMENTS)
CrossdeckContracts.withStatus(ContractStatus.PROPOSED)
CrossdeckContracts.findByTestName("identify B makes A entitlements unreachable from in-memory")
CrossdeckContracts.sdkVersion       // "1.4.1"
CrossdeckContracts.bundledIn        // "com.crossdeck:crossdeck:1.4.1"
```

The `Contract` data class + `ContractPillar`/`ContractStatus`/`ContractAppliesTo` enums are public. The binary-stability promise (which fields are guaranteed across patch/minor releases) is documented inline on `Contracts.kt` and in the monorepo's [`contracts/README.md`](https://github.com/VistaApps-za/crossdeck/blob/main/contracts/README.md).

### `cd.reportContractFailure(input)` — surface contract test failures

When a contract test asserts and fails — in your CI, a dogfood run, or a customer integration test — fire a typed `crossdeck.contract_failed` event over the **Crossdeck reliability channel**. This is one-way operational telemetry to the Crossdeck operations team (Privacy Policy §6, "Flow B"); it never enters your `track(...)` pipeline, never shows in your dashboard, never bills against your event quota. The wire shape is schema-locked at [`contracts/diagnostics/contract-failed-payload-schema-lock.json`](https://github.com/VistaApps-za/crossdeck/blob/main/contracts/diagnostics/contract-failed-payload-schema-lock.json):

```kotlin
import com.crossdeck.ContractFailureInput
import com.crossdeck.ContractFailureRunContext

cd.reportContractFailure(ContractFailureInput(
    contractId = "per-user-cache-isolation",
    failureReason = "expected isolation across user switch, got cross-read",
    runContext = if (System.getenv("CI") != null)
        ContractFailureRunContext.CI
    else
        ContractFailureRunContext.DOGFOOD,
    runId = System.getenv("GITHUB_RUN_ID") ?: java.util.UUID.randomUUID().toString(),
    testRef = ContractTestRef(
        file = "EntitlementCacheIsolationTest.kt",
        name = "identify B makes A entitlements unreachable from in-memory",
    ),
))
```

No new endpoint, no special ingest path — the event lands in the same pipeline every other `track(...)` call does. It surfaces immediately in the Crossdeck dashboard's live event feed, the breakdown chart (group by `contract_id`, `sdk_platform`), and any alert rule with `event = crossdeck.contract_failed`.

Properties stamped on the wire:

| Property | Source |
|----------|--------|
| `contract_id` | caller |
| `sdk_version`, `sdk_platform` | auto-stamped (Android ships `sdk_platform: "android"`) |
| `failure_reason`, `run_context`, `run_id` | caller |
| `test_file`, `test_name` | set when `testRef` is provided |
| `device_class` | optional, set by caller (categorical bucket — e.g. `"phone"`, `"tablet"`, `"tv"`, `"emulator"`) |

The wire shape is schema-locked at [`contracts/diagnostics/contract-failed-payload-schema-lock.json`](https://github.com/VistaApps-za/crossdeck/blob/main/contracts/diagnostics/contract-failed-payload-schema-lock.json); per-SDK assertion tests gate it on every release. Free-form `extra` keys are not accepted — adding a field requires an amendment to the schema-lock contract first.

`runContext` is one of `CI`, `DOGFOOD`, `CUSTOMER_APP` — the wire vocabulary matches the other SDKs so dashboards collapse cleanly across platforms. For a JUnit `TestWatcher`-driven test reporter that emits one event per failed contract test, see [`contracts/README.md` § Reporting contract failures](https://github.com/VistaApps-za/crossdeck/blob/main/contracts/README.md#reporting-contract-failures-back-to-crossdeck).

## Platforms

- Android 5.0+ (API 21+)
- Kotlin 1.9+, Java 17 toolchain
- StoreKit-style purchase rail: Apple supported via `rail = APPLE`
  on Apple platforms; Google Play (`rail = GOOGLE`) ships in v1.1

## Dependencies

**One.** The only runtime dep is `kotlinx-coroutines-android` —
every modern Android library already pulls it in (AndroidX uses
it). HTTP goes through `java.net.HttpURLConnection` (zero-dep,
on the platform); JSON through `org.json` (also on the platform).
No OkHttp / Moshi / kotlinx-serialization — your build never
inherits a version pin from us.

## License

MIT — see [LICENSE](./LICENSE).
