# Changelog

All notable changes to `@cross-deck/android` will be documented in
this file. Format follows [Keep a Changelog](https://keepachangelog.com/);
this project adheres to [Semantic Versioning](https://semver.org/).

## [1.3.0] — 2026-05-25

Bank-grade identity lock — the Android applicationId / package
name is now sent on every request and enforced server-side,
mirroring the Origin lock the Web SDK has always had and the
Bundle ID lock the Swift SDK v1.3.0 ships.

### Added — Package name identity claim

Every HTTP request the SDK fires now carries an
`X-Crossdeck-Package-Name` header sourced from
`Context.getPackageName()` — the OS-canonical applicationId
Android itself uses for Play Store identity.

The Crossdeck backend's `isPackageNameAllowed()` validator
enforces this against the packageName stored on the Android app
key. Requests without the header, or with a mismatched value,
are rejected with `403 / package_name_not_allowed`.

Bank-grade contract — same shape as web Origin / iOS Bundle ID:
- empty stored packageName on the key → request rejected
- missing header on the request → request rejected
- exact-match required

### Changed — `HttpClient` constructor signature

The internal `HttpClient` constructor now requires a `packageName`
parameter. Consumers who construct `HttpClient` directly (rare —
the public API is `Crossdeck.start(context, options)` which
handles this) must pass `context.packageName`.

### Migration

Customers must:
1. Bump the Gradle / Maven coordinate to `com.crossdeck:crossdeck:1.3.0`.
2. Rebuild + ship through Google Play.
3. Confirm `apps.android.packageName` is set on the project's
   Android app in the Crossdeck dashboard (Apps → Package name
   editor).

Apps shipped with v1.2.0 or earlier will start receiving 403s
once the backend enforcement deploys.

## [1.2.0] — 2026-05-25

Full bank-grade parity with Web/Node/RN/Swift v1.2.0. v1.0.x shipped
the manual contracts (track / identify / error capture / entitlements);
v1.2.0 closes every audit gap on the *automatic* surface — auto-
tracking, perf vitals, app-lifecycle plumbing, crash-on-relaunch,
network reachability, plus the missing API symmetry (`group`,
`consentStatus`, `respectDnt`).

Aligns Android with the Swift SDK v1.2.0 cross-platform event
vocabulary: `session.started`, `session.ended`, `page.viewed`,
`element.clicked` are the same names on every platform, so a single
dashboard query for any of them returns Web + iOS + Android rows
uniformly. The `platform` property (added by [DeviceInfo] on every
event) discriminates when needed.

### Added — Auto-tracking (sessions + screens + taps)

- `session.started` / `session.ended` with `sessionId`, `durationMs`,
  `reason`. 30-minute idle threshold matches GA4 / Mixpanel / Web SDK
  convention — a quick app-switch keeps the same session.
- `page.viewed` — fires automatically on every `Activity.onResume`,
  skipping framework hosts (`androidx.*`, `android.*`,
  `com.android.*`) and dedup'd by class name within 250ms so push/pop
  animations don't double-fire.
- `element.clicked` — fires on tap events captured via a
  `Window.Callback` wrapper. Walks the hit-test tree up to 8 levels
  deep to find the deepest interactive view (isClickable or
  contentDescription set). Captures element class, content
  description, resource id, view text, and viewport coordinates.

Every event the SDK ships is enriched with the current `sessionId`
so funnels reconstruct without explicit instrumentation.

Privacy guardrails baked in:
- Accessibility labels matching `password` / `card` / `ssn` /
  `credit` / `cvv` / `pin` are skipped silently.
- Per-element opt-out: set `view.contentDescription = "cd-noTrack ..."`
  and Crossdeck skips it. Walks 6 ancestors so a parent container
  opt-out covers a whole subtree.
- 100ms tap-coalesce defeats synthetic double-fires.

Configurable via `CrossdeckOptions(autoTrack = AutoTrackConfig.OFF)`
for strict-consent flows, or per-feature toggles via the
`AutoTrackConfig` data-class constructor.

### Added — Performance vitals (opt-in)

Mirrors the Swift SDK's MetricKit integration and the Web SDK's
web-vitals module. Set `enablePerformanceMonitoring = true` to
receive:

- `perf.cold_launch_ms` — once per process, measured from
  `Process.getStartElapsedRealtime` to the first foreground
  `ProcessLifecycleOwner.onStart`.
- `perf.anr` — main-thread blocked > 5 seconds (matches Android's
  own ANR threshold). Includes a 40-frame stack trace.
- `perf.frame_jank` — 60-second rollup of Choreographer frame
  drops: total frames, slow (>16ms), frozen (>700ms), with %
  metrics aligned to Android Vitals' "Slow rendering" / "Frozen
  frames" definitions.

API 21+ for everything except cold launch, which needs API 24+
(falls back to no signal below).

### Added — `ProcessLifecycleOwner` foreground/background

Replaces per-Activity lifecycle as the source of truth for
"app foregrounded / backgrounded." Multi-Activity backstacks no
longer trigger spurious session events on transitions between
Activities within the same process foreground.

### Added — Network reachability flush

`ConnectivityManager.NetworkCallback` watches reachability and
fires `queue.flush()` on `offline → online` transitions. Default ON.

### Added — Crash-on-relaunch persistence

Fatal exceptions are persisted to `<filesDir>/crossdeck-fatal.json`
synchronously inside the uncaught-exception handler, BEFORE the
chain forwards to Crashlytics / Sentry. On next launch, the SDK
reads the file and emits an `$error.recovered` event. Default ON.

### Added — Deep-link / push interaction tracking helpers

Public extension functions: `cd.trackDeepLink(uri)`,
`cd.trackDeepLinkIntent(intent)`, `cd.trackPushReceived(data)`,
`cd.trackPushInteraction(data, actionId)`. Extracts UTM + click-id
query parameters (gclid, fbclid, msclkid, ttclid, li_fat_id,
twclid) as top-level properties. PII protection: alert body is
never logged.

### Added — Google Play Billing helpers

`cd.handleBillingResult(responseCode, purchasesList)` forwards every
signed purchase to `/purchases/sync` via the same backend contract
`syncPurchases()` uses, AND fires a public funnel event
(`purchase.completed` / `purchase.refunded` / `purchase.failed`).
Consumer calls this from their own `PurchasesUpdatedListener`.

### Added — API symmetry with Web SDK

- `cd.group(groupType, groupKey, traits)` — Mixpanel-style B2B
  group analytics. Sets a super-property and fires `group.set`.
- `cd.consentStatus(): ConsentState` — read current consent state
  so the consumer can render an opt-out toggle.
- `cd.resetSession()` — force a new sessionId immediately (logout
  flows).
- `CrossdeckOptions(respectDnt = true)` — immutable opt-out
  (CCPA-style). Sets `analytics = false, errors = false` immediately
  after start, cannot be re-enabled at runtime.

### Fixed — pre-existing Kotlin 2.0 compile errors

Two functions in `EntitlementCache.kt` + `EventQueue.kt` used the
expression-body-with-return pattern that Kotlin 2.0 rejects. Both
converted to block bodies with explicit return statements; behaviour
unchanged.

### Migration

Strictly additive. All v1.0.x call sites compile clean. New modules
are default-OFF where they could be surprising. Opt out of any
default-ON module:

```kotlin
CrossdeckOptions(
    autoTrack = AutoTrackConfig.OFF,
    enableReachabilityFlush = false,
    enableCrashOnRelaunch = false,
)
```

## [1.0.1] — 2026-05-25

KPMG/PwC-grade audit pass on the v1.0.0 surface. Every finding the
audit flagged is closed in this release; no public API breakage.

### Fixed — bank-grade contract violations

- **`Crossdeck.stop()` now cancels the internal `CoroutineScope`.**
  v1.0.0 cancelled the periodic flush job and unregistered the
  lifecycle observer but left the `SupervisorJob + Dispatchers.IO`
  scope alive forever. Multiple `start()`/`stop()` cycles (e.g.
  logout/login) leaked a scope + dispatcher reference per cycle.
  Now `stop()` blocks on a final `queue.persistAll()` (durability
  wins over latency on the teardown path), then `scope.cancel()`.
- **`setContext(name, data)` now stores a defensive copy.**
  Previously a caller mutating the same `Map<String,String>` they
  passed in would retroactively change what an already-captured
  error reports. Now `data.toMap()` is stored so the snapshot is
  frozen at the call site. Matches Web/Node/RN/Swift.

### Documented — auditor-friendly comments

- **408 / 429 carve-out in `Http.classifyResponse`.** The 4xx hard-
  stop rule explicitly exempts `408 Request Timeout` and
  `429 Rate Limit` (those are retryable). v1.0.0 had the carve-out
  in code but no comment; v1.0.1 documents the rationale + the
  cross-SDK parity claim so a future maintainer doesn't accidentally
  remove the exemption and start silently dropping rate-limited
  batches.
- **`SimpleDateFormat` thread-safety note in `parseRetryAfterHeader`.**
  The JDK class is not thread-safe; the code constructs a fresh
  instance per call, which is correct but used to be undocumented.
  Now there's an explicit comment so a future refactor doesn't
  cache it module-level (which would be a concurrent-retry bug).

### Notes

- Public API is fully additive — every v1.0.0 caller still compiles.
- No wire-shape changes; same headers, same envelope, same paths.
- The Sdk.VERSION constant + the Maven publication version are
  bumped to `1.0.1`. The `Crossdeck-Sdk-Version` header on every
  request now reads `@cross-deck/android@1.0.1`.

## [1.0.0] — 2026-05-25

Initial release. Brings the Crossdeck Android SDK to the same
bank-grade contract as the Web, Node, React Native, and Swift SDKs.

### Event ingestion

- Durable, deduplicated, batched event queue. Pending batch lives
  in a dedicated slot held across retries — a crash mid-flight does
  NOT lose the batch; it rehydrates from `SharedPreferences` on
  relaunch and re-sends with the original `Idempotency-Key`.
- 4xx hard stop. Permanent failures (`invalid_request_error`,
  `authentication_error`, `permission_error`) drain through the
  `onPermanentFailure` callback and never block newer events.
- `Retry-After` honoured even above the local `maxMs`, clamped at
  24h as a sanity ceiling.
- Buffer overflow drops OLDEST events, preserving the most-recent
  diagnostic signal.
- Periodic flush ticker fires every `queueConfig.flushIntervalMs`
  (default 5s) so an idle app still ships its events.
- `ActivityLifecycleCallbacks` triggers `persistAll()` + `flush()`
  on every `onActivityPaused` so the few seconds before suspension
  always include a drain.

### Error capture

- Uncaught `Throwable` handler installs an SDK-aware bridge AND
  chains into the prior `Thread.getDefaultUncaughtExceptionHandler()`
  so Crashlytics / Sentry / Bugsnag keep working.
- Manual `captureError(...)` for handled errors. Both paths attach
  a normalised stack (module:symbol fingerprint) + breadcrumb ring
  buffer.
- `beforeSend` hook for per-error filter / mutation; runtime-
  replaceable via `setErrorBeforeSend(...)`.
- Self-request detection: HTTP failures against the SDK's own
  ingest endpoint are skipped to prevent feedback loops.

### Identity + entitlements

- `anonymousId` persisted in `SharedPreferences`, regenerated only
  on `reset()`.
- `identify(...)` unconditionally clears the entitlement cache so
  a switched-customer never inherits the prior user's entitlements.
- Entitlement cache scoped on `(developerUserId, entitlements)` —
  reads for a different customer return `null`, never the wrong
  set.
- `isEntitled(...)` synchronous, never blocks on network. Outage =
  preserve last-known-good (`markRefreshFailed` records the failure
  without invalidating the cache).

### Privacy

- PII scrubber on by default. `<email>` and `<card>` tokens (angle-
  bracketed) match the platform-wide vocabulary across Web/Node/RN/
  Swift and backend.
- Recursive walk over nested maps + lists, depth-guarded at 64.
- Default-grant consent state — both analytics and errors on by
  default. Wire `setConsent(...)` for opt-out flows.
- Sensitive-property warnings (email/password/token/secret/card/
  phone/credit_card patterns) surface via the debug logger without
  blocking the event.

### Threading + lifecycle

- Public API safe to call from any thread.
- Synchronous mutators (`track`, `identify`, `isEntitled`, `setTag`,
  etc.) take effect on the caller's thread before returning.
- Blocking HTTP is offloaded to `Dispatchers.IO` inside `suspend`
  methods (`forget`, `syncPurchases`, `getEntitlements`,
  `heartbeat`, `flush`).
- `track()` snapshots identity + super-properties + consent on the
  caller's thread BEFORE the background `Task` enqueues —
  eliminates the classic identify-then-track race.

### Build + distribution

- `minSdk = 21` (Android 5.0+). Covers ~99% of active devices.
- Java 17 toolchain, Kotlin 1.9+.
- Strict explicit-api (`-Xexplicit-api=strict`) — every public
  class declares visibility explicitly.
- One runtime dependency: `kotlinx-coroutines-android`. HTTP via
  `java.net.HttpURLConnection`; JSON via `org.json`. No OkHttp /
  Moshi / kotlinx-serialization pin inherited by consumers.
- Maven publication: `com.crossdeck:crossdeck:1.0.0`.
