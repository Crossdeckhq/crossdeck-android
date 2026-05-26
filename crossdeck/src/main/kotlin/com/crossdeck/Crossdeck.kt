// Crossdeck Android SDK — public client facade.
//
// This file is the developer-facing entry point. Everything else
// in this package is a building block; the only type a consumer
// touches is [Crossdeck] (via the [Crossdeck.start] factory).
//
// **Cross-SDK contract.** Every public method here mirrors the
// Web SDK (sdks/web/src/crossdeck.ts), Node SDK
// (sdks/node/src/crossdeck-server.ts), React Native SDK
// (sdks/react-native/src/), and Swift SDK
// (sdks/swift/Sources/Crossdeck/Crossdeck.swift) byte-for-byte:
//
//   * Identical method names + parameter ordering
//   * Identical wire shapes (envelope JSON, headers, paths)
//   * Identical semantics for identify cache-clear, 4xx hard-stop,
//     queue durability, PII scrub depth, beforeSend hook, consent
//     gates, sensitive-property warnings, self-request skip
//
// Drift here is a cross-SDK consistency bug — a customer running
// Web + Android side-by-side would observe different behaviour
// for the same call, which breaks the platform-wide contract.
// Any change here should land on Web/Node/RN/Swift simultaneously.
//
// **Threading.** Public mutators that don't touch the network are
// synchronous (they take effect before the call returns) — this
// matches the Swift/Web/Node/RN contract that `track()` followed
// immediately by `identify()` sees the new identity, and that a
// `track()` enqueued just before app-background is fully visible
// to a subsequent `persistAll()`. Public methods that ship HTTP
// are `suspend` and offload to `Dispatchers.IO`.

package com.crossdeck

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Crossdeck client configuration.
 *
 * Built with sensible defaults — the minimum a consumer must
 * provide is `appId`, `publicKey`, and `environment`. Everything
 * else has a default that matches the platform-wide bank-grade
 * contract (consent default-grant, PII scrub on, etc.).
 */
public data class CrossdeckOptions(
    /**
     * Crossdeck App ID issued in the dashboard (e.g.
     * `app_android_xxx`). Required. Goes on every batch envelope so
     * the backend can correlate events with the specific app
     * surface and reject mismatched env declarations
     * (`env_mismatch`).
     */
    val appId: String,

    /**
     * Crossdeck publishable key (`cd_pub_live_…` /
     * `cd_pub_test_…`). Required. Safe to embed in a shipped APK —
     * can only POST events and read entitlements, never grant
     * features or read other customers' data.
     */
    val publicKey: String,

    /**
     * Explicit environment declaration. Required. Must match the
     * `publicKey` prefix — `cd_pub_live_…` ↔ [Environment.PRODUCTION],
     * `cd_pub_test_…` ↔ [Environment.SANDBOX]. Mismatch is rejected
     * at [Crossdeck.start].
     */
    val environment: Environment,

    /**
     * Override the API base URL. Default
     * `https://api.cross-deck.com/v1`. Useful for self-hosted
     * setups or the local emulator. When overridden, the SDK's
     * error-capture self-skip pivots off THIS URL's hostname —
     * matches Web/Node/RN/Swift "self-skip from baseUrl" (Batch C
     * audit fix).
     */
    val baseUrl: String? = null,

    /**
     * Storage backend. Defaults to a [SharedPreferencesStorage]
     * bound to the supplied [Context]. Override for in-memory
     * tests ([MemoryStorage]) or a multi-process / encrypted
     * storage adapter.
     */
    val storage: KeyValueStorage? = null,

    /**
     * Initial consent state. Default-grant both channels —
     * matches Web/Node/RN/Swift. Consumers wire opt-out via
     * [Crossdeck.setConsent] for strict-consent flows.
     */
    val initialConsent: ConsentState = ConsentState(),

    /**
     * Scrub PII before events leave the device. On by default —
     * emails and card numbers in property values are replaced
     * with `<email>` / `<card>` tokens before the event enters
     * the queue. Disable only with hard requirement + explicit
     * consent.
     */
    val scrubPii: Boolean = true,

    /** Queue configuration (batch size, flush interval, retry). */
    val queueConfig: EventQueueConfig = EventQueueConfig(),

    /** Breadcrumb ring-buffer capacity. */
    val breadcrumbCapacity: Int = DEFAULT_BREADCRUMB_CAPACITY,

    /**
     * Capture uncaught JVM exceptions. Default off — installing
     * the global handler can interfere with Crashlytics / Sentry
     * (we chain into the prior handler, but some setups still
     * prefer a single owner). Turn on only when Crossdeck is your
     * primary error tracker.
     */
    val captureUncaughtExceptions: Boolean = false,

    /**
     * Filter / transform errors before they're enqueued. Returning
     * null drops the error entirely. Replaceable at runtime via
     * [Crossdeck.setErrorBeforeSend].
     */
    val beforeSendError: BeforeSendErrorHandler? = null,

    /**
     * Permanent-failure callback. Wired into the queue so the
     * consumer can observe events that will never deliver.
     */
    val onPermanentFailure: PermanentFailureHandler? = null,

    /**
     * Debug log routing. Default is a no-op so prod builds carry
     * no log overhead. Pass [DefaultDebugLogger] to route to
     * `android.util.Log` during development.
     */
    val debugLogger: DebugLogger = NoopDebugLogger,

    /**
     * Auto-tracking configuration. Default-everything-on — sessions,
     * screen views via `page.viewed`, tap autocapture via
     * `element.clicked`. Pass [AutoTrackConfig.OFF] for strict-
     * consent flows where the SDK must emit zero events before
     * user opt-in.
     *
     * Cross-platform contract: same event names as the Web/Node/RN/
     * Swift SDKs — `session.started`, `session.ended`, `page.viewed`,
     * `element.clicked` — so a single dashboard query for any of
     * these names returns Web + iOS + Android rows uniformly. The
     * `platform` property added by the device-info enricher
     * discriminates when needed.
     */
    val autoTrack: AutoTrackConfig = AutoTrackConfig.DEFAULT,

    /**
     * Cold launch time / ANR detection / frame jank instrumentation.
     * Off by default — the watchdog thread + Choreographer callback
     * carry a small ongoing cost that not every customer wants.
     * Mirrors Swift's MetricKit integration. When true, emits
     * `perf.cold_launch_ms`, `perf.anr`, `perf.frame_jank` events.
     */
    val enablePerformanceMonitoring: Boolean = false,

    /**
     * Proactively flush the event queue whenever network
     * reachability transitions from offline → online. Default ON
     * because the latency improvement on intermittent connections
     * is large and [ConnectivityManager.NetworkCallback] has near-
     * zero overhead.
     */
    val enableReachabilityFlush: Boolean = true,

    /**
     * Persist fatal exceptions to disk during the uncaught-exception
     * handler so they survive process death. On next launch, the
     * SDK reads the file and emits the captured fatal through the
     * normal pipeline. Default ON — fatal crashes are the events
     * most worth preserving, and the persist cost (one tiny file
     * write) is negligible.
     */
    val enableCrashOnRelaunch: Boolean = true,

    /**
     * Respect a global "do not track" signal. Mirrors Web SDK's
     * `respectDnt`. When true, the SDK behaves as if
     * `setConsent(ConsentState(analytics=false, errors=false))`
     * was called immediately after start — and the consent
     * cannot be re-enabled at runtime. Use this when the consumer
     * needs an immutable opt-out (CCPA compliance).
     */
    val respectDnt: Boolean = false,
) {
    /**
     * Effective base URL — `baseUrl` if set, else the production
     * default. Used by the HTTP client and the self-request skip.
     */
    public val effectiveBaseUrl: String
        get() = baseUrl ?: "https://api.cross-deck.com/v1"
}

/**
 * Crossdeck client — the single instance a consumer holds for the
 * app's lifetime. Hold it in a singleton (or your DI container)
 * and reuse it; constructing a second one will install a second
 * queue + a second pair of lifecycle observers.
 *
 * **Thread safety.** Public API is safe to call from any thread.
 * Synchronous mutators take effect immediately on the caller's
 * thread (so a subsequent `track()` always observes them);
 * blocking HTTP is offloaded to [Dispatchers.IO] inside the
 * `suspend` methods.
 */
public class Crossdeck private constructor(
    private val options: CrossdeckOptions,
    private val storage: KeyValueStorage,
    private val identity: Identity,
    private val superProperties: SuperProperties,
    private val entitlements: EntitlementCache,
    private val consent: ConsentManager,
    private val breadcrumbs: Breadcrumbs,
    private val queue: EventQueue,
    private val http: HttpClient,
    private val device: DeviceInfo,
    private val selfHostname: String?,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Lifecycle / started flag. `volatile` so reads from any
     * thread observe writes from `stop()` without a fence; mutating
     * paths still grab [startedLock] to ensure `stop()` ↔ method-
     * entry race resolves consistently.
     */
    @Volatile
    private var started: Boolean = true
    private val startedLock = Any()

    /**
     * Runtime-mutable error capture context. Protected by
     * [errorStateLock]. The [ErrorCapture] pipeline reads through
     * these on every captured event so `setTag` / `setContext` /
     * `setErrorBeforeSend` take effect for the NEXT error after
     * the call — matches Web/Node/RN/Swift.
     */
    private val errorStateLock = Any()
    private var errorTags: MutableMap<String, String> = mutableMapOf()
    private var errorContext: MutableMap<String, Map<String, String>> = mutableMapOf()
    private var runtimeBeforeSend: BeforeSendErrorHandler? = null

    /** Periodic flush job — cancelled on `stop()`. */
    private var flushTickerJob: Job? = null

    /** Lifecycle hook — held for clean unregister on `stop()`. */
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null

    /** AutoTracker unregister handle — call from `stop()` to drop
     *  this instance's listener. AutoTracker singleton stays
     *  installed for the process lifetime (un-installing is race-
     *  prone), but no more events flow to this client.
     */
    private var autoTrackUnregister: (() -> Unit)? = null

    /** Performance vitals — null when [CrossdeckOptions.enablePerformanceMonitoring] is false. */
    private var performanceVitals: PerformanceVitals? = null

    /** Reachability monitor — null when [CrossdeckOptions.enableReachabilityFlush] is false. */
    private var reachability: Reachability? = null

    /** Crash-on-relaunch persister — null when [CrossdeckOptions.enableCrashOnRelaunch] is false. */
    private var crashOnRelaunch: CrashOnRelaunch? = null

    public companion object {

        /**
         * Designated start path. Validates configuration, allocates
         * sub-modules, rehydrates the queue from disk, optionally
         * installs the global exception handler, and posts the
         * boot heartbeat.
         *
         * @throws CrossdeckError with code `invalid_secret_key` if
         *   the publicKey shape is wrong, `env_mismatch` if the
         *   environment doesn't match the key prefix, or
         *   `missing_app_id` if `appId` is empty.
         */
        @JvmStatic
        @Throws(CrossdeckError::class)
        public fun start(context: Context, options: CrossdeckOptions): Crossdeck {
            validateOptions(options)
            val appCtx = context.applicationContext ?: context
            val storage = options.storage ?: SharedPreferencesStorage(appCtx)
            val identity = Identity(storage)
            val superProps = SuperProperties(storage)
            val entitlementCache = EntitlementCache(storage)
            val consentMgr = ConsentManager(
                initial = options.initialConsent,
                initialScrubPii = options.scrubPii,
            )
            val crumbs = Breadcrumbs(options.breadcrumbCapacity)
            val httpClient = HttpClient(
                baseUrl = options.effectiveBaseUrl,
                publicKey = options.publicKey,
                // Android package name = OS-canonical applicationId.
                // Sent as X-Crossdeck-Package-Name on every request so
                // the backend can enforce the bank-grade identity lock
                // against the packageName stored on the app key.
                packageName = appCtx.packageName ?: "",
            )
            val queue = EventQueue(
                http = httpClient,
                storage = storage,
                envelope = BatchEnvelope(
                    appId = options.appId,
                    environment = options.environment,
                ),
                logger = options.debugLogger,
                onPermanentFailure = options.onPermanentFailure,
                config = options.queueConfig,
            )
            val device = DeviceInfo.capture(appCtx)
            // Self-skip pivots on the configured base URL, NOT the
            // production default — so a staging or self-hosted
            // relay never feeds its own error-capture loop.
            val selfHostname = extractSelfHostname(options.effectiveBaseUrl)

            val client = Crossdeck(
                options = options,
                storage = storage,
                identity = identity,
                superProperties = superProps,
                entitlements = entitlementCache,
                consent = consentMgr,
                breadcrumbs = crumbs,
                queue = queue,
                http = httpClient,
                device = device,
                selfHostname = selfHostname,
            )

            options.debugLogger(
                DebugSignal.SDK_CONFIGURED,
                mapOf(
                    "platform" to device.platform,
                    "sdk_version" to Sdk.VERSION,
                ),
            )

            // ALWAYS wire the manual error-capture routing closure so
            // cd.captureError(...) works on every project. The global
            // JVM Thread.setDefaultUncaughtExceptionHandler is gated
            // separately by options.captureUncaughtExceptions, so
            // Crashlytics / Sentry consumers keep their primary
            // global handler intact while manual captures still flow.
            client.installErrorCapture()
            client.installLifecycleObservers(appCtx)
            client.startFlushTicker()

            // ---- Auto-tracking & ambient signal modules (v1.2.0) ----
            //
            // Same shape as the Swift SDK v1.2.0 wiring. Each module
            // is independently opt-out via CrossdeckOptions flags.

            // 1. Crash-on-relaunch — must run BEFORE any other
            //    module so we ingest yesterday's fatal before
            //    today's first event hits the queue.
            if (options.enableCrashOnRelaunch) {
                val persister = CrashOnRelaunch(appCtx.filesDir)
                client.crashOnRelaunch = persister
                val persistedFatal = persister.consumePersistedFatal()
                if (persistedFatal != null) {
                    client.scope.launch {
                        try {
                            // Emit as a regular tracked event — the
                            // backend handles `$error` events
                            // uniformly. Adds `recovered: true` to
                            // mark this as a process-restart record.
                            val props = persistedFatal.toMutableMap()
                            props["recovered"] = true
                            client.track("\$error.recovered", props)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            // 2. AutoTracker — sessions / screens / taps.
            if (appCtx is Application) {
                val emit: (String, Map<String, Any?>) -> Unit = { name, props ->
                    try {
                        client.track(name, props)
                    } catch (_: Throwable) {
                    }
                }
                client.autoTrackUnregister = AutoTracker.shared.register(
                    application = appCtx,
                    config = options.autoTrack,
                    emit = emit,
                )
            }

            // 3. Reachability — flush on offline → online.
            if (options.enableReachabilityFlush) {
                val reach = Reachability(appCtx) {
                    client.scope.launch { client.queue.flush() }
                }
                reach.start()
                client.reachability = reach
            }

            // 4. Performance vitals — cold launch + ANR + jank.
            if (options.enablePerformanceMonitoring && appCtx is Application) {
                val emit: (String, Map<String, Any?>) -> Unit = { name, props ->
                    try {
                        client.track(name, props)
                    } catch (_: Throwable) {
                    }
                }
                val perf = PerformanceVitals(appCtx, emit)
                perf.start()
                client.performanceVitals = perf
            }

            // 5. respectDnt — immutable opt-out, set immediately
            //    after start so no events fire between options
            //    parse and consent denial.
            if (options.respectDnt) {
                client.setConsent(
                    ConsentState(analytics = false, errors = false),
                )
            }

            // Fire-and-forget post-boot work: flush anything
            // rehydrated from the prior session, then heartbeat
            // so the dashboard's onboarding checklist flips LIVE.
            client.scope.launch {
                client.queue.flush()
                client.heartbeatInternal()
            }
            return client
        }

        private fun validateOptions(options: CrossdeckOptions) {
            // publicKey must start with cd_pub_ — backend rejects
            // any other shape on the first authenticated request
            // anyway; failing fast here gives a clearer error.
            if (!options.publicKey.startsWith("cd_pub_")) {
                throw CrossdeckError(
                    type = CrossdeckErrorType.AUTHENTICATION,
                    code = "invalid_secret_key",
                    message = "Crossdeck.start requires a publishable key starting with cd_pub_. " +
                        "Got prefix: ${options.publicKey.take(8)}…",
                )
            }
            // Env must match key prefix — typo'd build config
            // would otherwise silently route production telemetry
            // into a sandbox dashboard.
            val expectedEnv = if (options.publicKey.startsWith("cd_pub_live_")) {
                Environment.PRODUCTION
            } else {
                Environment.SANDBOX
            }
            if (options.environment != expectedEnv) {
                throw CrossdeckError(
                    type = CrossdeckErrorType.INVALID_REQUEST,
                    code = "env_mismatch",
                    message = "publicKey prefix declares ${expectedEnv.wireValue} but " +
                        "options.environment is ${options.environment.wireValue}. " +
                        "Fix one or the other before start.",
                )
            }
            if (options.appId.isEmpty()) {
                throw CrossdeckError(
                    type = CrossdeckErrorType.INVALID_REQUEST,
                    code = "missing_app_id",
                    message = "Crossdeck.start requires a non-empty appId. " +
                        "Find yours in the Crossdeck dashboard.",
                )
            }
        }
    }

    // ===============================================================
    // Public API — analytics
    // ===============================================================

    /**
     * Enqueue an analytics event. Sanitises property values that
     * aren't JSON-encodable / are oversize / are cyclic (NEVER
     * throws on a single bad property — matches the platform-
     * wide contract that `track()` is total).
     *
     * Throws only on caller error (empty name) or after `stop()`.
     */
    @Throws(CrossdeckError::class)
    public fun track(name: String, properties: Map<String, Any?>? = null) {
        assertStarted()
        if (name.isEmpty()) {
            throw CrossdeckError(
                type = CrossdeckErrorType.INVALID_REQUEST,
                code = "missing_event_name",
                message = "track(name) requires a non-empty name.",
            )
        }
        val sanitised: Map<String, Any?> = if (properties != null) {
            val v = validateEventProperties(properties)
            for (warning in v.warnings) {
                options.debugLogger(
                    DebugSignal.SDK_PROPERTY_COERCED,
                    mapOf(
                        "key" to warning.key,
                        "kind" to warning.kind.wireValue,
                    ),
                )
            }
            v.properties
        } else {
            emptyMap()
        }

        // SNAPSHOT identity + consent + super-properties on the
        // CALLER'S thread BEFORE we hop to a background task. This
        // eliminates the classic identify+track race: a track call
        // following identify always observes the post-identify
        // developerUserId on the wire (matches Web/Node/RN/Swift).
        val consentSnapshot = consent.state
        if (!consentSnapshot.analytics) {
            options.debugLogger(
                DebugSignal.SDK_CONSENT_DENIED,
                mapOf("event" to name),
            )
            return
        }

        // Warn (don't block) on property names that look like PII
        // or secrets. Patterns mirror Web/Node/RN/Swift exactly so
        // cross-platform teams get identical warnings.
        val sensitiveHits = findSensitivePropertyKeys(properties)
        if (sensitiveHits.isNotEmpty()) {
            options.debugLogger(
                DebugSignal.SDK_SENSITIVE_PROPERTY_WARNING,
                mapOf(
                    "event" to name,
                    "keys" to sensitiveHits.joinToString(","),
                ),
            )
        }
        val scrubEnabled = consent.scrubPiiEnabled
        val idSnap = identity.snapshot()
        val superPropsSnap = superProperties.snapshot()
        val devicePayload = device.asPayload()
        // Capture sessionId from AutoTracker so every event tracks
        // its session anchor — same enrichment Swift v1.2.0 does.
        // Empty when sessions auto-track is off; consumers can still
        // hand-supply via super-properties.
        val sessionIdSnap = AutoTracker.shared.currentSessionId()

        scope.launch {
            // Merge order matters: super-properties (set by dev) <
            // device payload (SDK-provided context) < caller
            // properties (most specific). Last write wins — matches
            // Web/Node/RN/Swift.
            val merged = LinkedHashMap<String, Any?>(
                superPropsSnap.size + devicePayload.size + sanitised.size + 1,
            )
            for ((k, v) in superPropsSnap) merged[k] = v
            for ((k, v) in devicePayload) merged[k] = v
            for ((k, v) in sanitised) merged[k] = v
            // sessionId LAST so auto-track anchor wins over any
            // caller-supplied "sessionId" key. Skipped when no
            // session is active.
            if (sessionIdSnap != null) merged["sessionId"] = sessionIdSnap

            val final = if (scrubEnabled) {
                @Suppress("UNCHECKED_CAST")
                (scrubPiiDeep(merged) as? Map<String, Any?>) ?: merged
            } else {
                merged
            }

            val event = WireEvent(
                id = makeEventId(),
                name = name,
                timestampMs = System.currentTimeMillis(),
                properties = final,
                anonymousId = idSnap.anonymousId,
                developerUserId = idSnap.developerUserId,
                crossdeckCustomerId = idSnap.crossdeckCustomerId,
            )
            queue.enqueue(event)
            breadcrumbs.add(
                Breadcrumb(
                    category = BreadcrumbCategory.CUSTOM,
                    level = BreadcrumbLevel.INFO,
                    message = "track $name",
                ),
            )
        }
    }

    // ===============================================================
    // Public API — identity
    // ===============================================================

    /**
     * Link the device to a stable user identity. Sets
     * `developerUserId` synchronously and ALWAYS clears the
     * entitlement cache (bank-grade contract — a freshly-identified
     * user must never observe the prior user's entitlements via any
     * sync read path, even when identifying with the same id).
     *
     * Fires the `/identity/alias` POST in the background; the
     * canonical `cdcust_…` is persisted on success. Use
     * [identifyAndWait] when you need the cdcust_ before continuing.
     *
     * @throws CrossdeckError code `missing_user_id` if `userId` is
     *   empty, or `not_initialized` if called after `stop()`.
     */
    @Throws(CrossdeckError::class)
    public fun identify(
        userId: String,
        email: String? = null,
        traits: Map<String, Any?>? = null,
    ) {
        assertStarted()
        if (userId.isEmpty()) {
            throw CrossdeckError(
                type = CrossdeckErrorType.INVALID_REQUEST,
                code = "missing_user_id",
                message = "identify(userId) requires a non-empty userId.",
            )
        }
        val cleanedTraits = sanitiseTraits(traits, scope = "identify.traits")

        // SYNC: set developerUserId + switch entitlement cache to the
        // per-user storage slot BEFORE we return. v1.4.x bank-grade
        // three-layer isolation (matches Web/RN):
        //   (a) Physical key separation —
        //       `crossdeck:entitlements:<sha256(userId)>`
        //   (b) Unconditional in-memory wipe — flips suffix even on
        //       same-id re-identify; a tiny redundant cache rebuild
        //       is cheaper than a leak.
        //   (c) Re-hydrate from the new slot — returning user sees
        //       their last-known-good cache immediately.
        identity.setDeveloperUserId(userId)
        entitlements.setUserKey(userId)
        options.debugLogger(
            DebugSignal.SDK_CONFIGURED,
            mapOf("user_id" to userId),
        )
        breadcrumbs.add(
            Breadcrumb(
                category = BreadcrumbCategory.IDENTITY,
                level = BreadcrumbLevel.INFO,
                message = "identify $userId",
            ),
        )

        // Background alias POST. Best-effort — local identity is
        // already set; server-side merge will catch up on the next
        // identify or a backend reconciliation pass if the call
        // fails here.
        val idSnap = identity.snapshot()
        scope.launch {
            try {
                val result = postAliasIdentity(
                    AliasIdentityRequest(
                        userId = userId,
                        anonymousId = idSnap.anonymousId,
                        email = email,
                        traits = wireTraits(cleanedTraits),
                    ),
                )
                identity.setCrossdeckCustomerId(result.crossdeckCustomerId)
                options.debugLogger(
                    DebugSignal.SDK_CONFIGURED,
                    mapOf(
                        "alias" to "ok",
                        "cdcust" to result.crossdeckCustomerId,
                        "merge_pending" to result.mergePending.toString(),
                    ),
                )
            } catch (e: Throwable) {
                options.debugLogger(
                    DebugSignal.SDK_INVALID_KEY,
                    mapOf(
                        "alias" to "failed",
                        "error" to (e.message ?: e::class.java.simpleName),
                    ),
                )
            }
        }
    }

    /**
     * Synchronous identify + wait for the `/identity/alias`
     * round-trip. Use only when you need the canonical
     * `crossdeckCustomerId` before continuing (e.g. server-side
     * cross-reference at sign-in). [identify] already fires the
     * alias call in the background, so this awaits the network
     * trip rather than doing a separate enqueue.
     */
    @Throws(CrossdeckError::class)
    public suspend fun identifyAndWait(
        userId: String,
        email: String? = null,
        traits: Map<String, Any?>? = null,
    ): AliasResult {
        identify(userId, email, traits)
        val idSnap = identity.snapshot()
        val cleanedTraits = sanitiseTraits(traits, scope = "identify.traits")
        val result = postAliasIdentity(
            AliasIdentityRequest(
                userId = userId,
                anonymousId = idSnap.anonymousId,
                email = email,
                traits = wireTraits(cleanedTraits),
            ),
        )
        identity.setCrossdeckCustomerId(result.crossdeckCustomerId)
        return result
    }

    /**
     * GDPR right-to-be-forgotten. POSTs `/identity/forget` and
     * ALWAYS runs local cleanup, even on server error — matches
     * Web/Node/RN/Swift. The publishable-key forget flow may
     * return 401 for identified erasure (the backend requires
     * `idToken` which v1.0.0 doesn't yet ship); local wipe still
     * completes so the consumer's GDPR contract holds even when
     * the server-side erasure needs a follow-up via cd_sk_.
     */
    @Throws(CrossdeckError::class)
    public suspend fun forget() {
        assertStarted()
        val snap = identity.snapshot()
        val body = ForgetIdentityRequest(
            userId = snap.developerUserId,
            anonymousId = snap.anonymousId,
            customerId = snap.crossdeckCustomerId,
        )
        val outcome = httpRequest(
            method = "POST",
            path = "/identity/forget",
            body = encodeJsonBody(body),
            idempotencyKey = makeIdempotencyKey(),
        )

        // Local wipe FIRST — runs regardless of server outcome.
        // Logout-grade wipe: every per-user entitlement slot on the
        // device (matches Web/RN reset() semantics).
        identity.reset()
        entitlements.clearAll()
        superProperties.clear()
        breadcrumbs.clear()

        if (outcome.kind != HttpSendOutcome.Kind.SUCCESS) {
            // 401 from publishable-key identified erasure is the
            // documented carve-out — log + return (local state
            // is already clean, retry from backend with cd_sk_).
            if (outcome.envelope?.statusCode == 401) {
                options.debugLogger(
                    DebugSignal.SDK_INVALID_KEY,
                    mapOf(
                        "endpoint" to "/identity/forget",
                        "hint" to "Server requires idToken for identified erasure with cd_pub_; " +
                            "local state is wiped, retry server-side with cd_sk_.",
                    ),
                )
                return
            }
            throw outcome.error ?: CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "forget_failed",
                message = "/identity/forget did not succeed. Local state already wiped — server retry needed.",
            )
        }
    }

    // ===============================================================
    // Public API — purchases + entitlements
    // ===============================================================

    /**
     * Forward purchase evidence to the backend for verification
     * and entitlement projection. Wire this from Google Play
     * Billing or StoreKit transaction callbacks.
     *
     * v1.0.0 accepts `rail = AuditRail.APPLE`. `AuditRail.GOOGLE`
     * is reserved for v1.1 (backend currently returns
     * `google_not_supported`); we still accept it on the wire so
     * the caller's call-site doesn't change when the backend
     * lights up.
     */
    @Throws(CrossdeckError::class)
    public suspend fun syncPurchases(
        rail: AuditRail,
        signedTransactionInfo: String? = null,
        signedRenewalInfo: String? = null,
        purchaseToken: String? = null,
        appAccountToken: String? = null,
    ): PurchaseResult {
        assertStarted()
        // Snapshot identity for the post-sync cache warm. The
        // wire body carries no identity hints (server derives them
        // from the JWS / purchaseToken); we still need the
        // developerUserId locally to key the entitlement cache.
        val snap = identity.snapshot()
        val body = PurchaseSyncRequest(
            rail = rail.wireValue,
            signedTransactionInfo = signedTransactionInfo,
            signedRenewalInfo = signedRenewalInfo,
            purchaseToken = purchaseToken,
            appAccountToken = appAccountToken,
        )
        // Phase 2.2.c — deterministic Idempotency-Key from the
        // rail-stable identifier. Same JWS / purchaseToken → same
        // key → backend short-circuits with idempotent_replay:true
        // on retry. Falls back to the legacy random key only when
        // no identifier was supplied (shouldn't happen — the
        // public API validates one is present).
        val idempotencyKey = IdempotencyKey.deriveForPurchase(
            rail = rail.wireValue,
            signedTransactionInfo = signedTransactionInfo,
            purchaseToken = purchaseToken,
        ) ?: makeIdempotencyKey()
        val outcome = httpRequest(
            method = "POST",
            path = "/purchases/sync",
            body = encodeJsonBody(body),
            idempotencyKey = idempotencyKey,
        )
        val responseBody = outcome.envelope?.body
        if (outcome.kind != HttpSendOutcome.Kind.SUCCESS || responseBody.isNullOrEmpty()) {
            throw outcome.error ?: CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "sync_purchases_failed",
                message = "/purchases/sync did not succeed.",
            )
        }
        val result = decodePurchaseResult(responseBody)
        identity.setCrossdeckCustomerId(result.crossdeckCustomerId)
        if (snap.developerUserId != null) {
            entitlements.write(
                EntitlementSnapshot(
                    developerUserId = snap.developerUserId,
                    entitlements = result.entitlements,
                ),
            )
        }
        options.debugLogger(
            DebugSignal.SDK_PURCHASE_EVIDENCE_SENT,
            mapOf(
                "rail" to rail.wireValue,
                "entitlement_count" to result.entitlements.size.toString(),
            ),
        )
        // Phase 3.5 (v1.4.0) — emit purchase.completed so manual
        // syncPurchases callers show up on the same funnel as the
        // BillingAutoTrack path. Schema mirrors that path's event
        // name + rail/productId/subscriptionId so cross-path funnels
        // reconcile.
        val firstEnt = result.entitlements.firstOrNull()
        val props = mutableMapOf<String, Any?>("rail" to rail.wireValue)
        if (firstEnt != null) {
            props["productId"] = firstEnt.source.productId
            props["subscriptionId"] = firstEnt.source.subscriptionId
        }
        track("purchase.completed", props)
        return result
    }

    /**
     * Fetch the current entitlement set from the server and warm
     * the local cache. Returns the freshly-fetched set so the
     * caller can render UI immediately.
     *
     * On a 5xx / network failure the cache is preserved (last-
     * known-good wins) and the failure is recorded via
     * `markRefreshFailed` so [EntitlementCache.freshness] surfaces
     * it to UI. Bank-grade: never fail a paying customer down to
     * free because of a transient network blip.
     */
    @Throws(CrossdeckError::class)
    public suspend fun getEntitlements(): List<PublicEntitlement> {
        assertStarted()
        val snap = identity.snapshot()
        val userId = snap.developerUserId
            ?: throw CrossdeckError(
                type = CrossdeckErrorType.INVALID_REQUEST,
                code = "no_identity",
                message = "getEntitlements requires identify(userId) to have been called first.",
            )

        val query = buildMap<String, String> {
            put("userId", userId)
            snap.crossdeckCustomerId?.let { put("customerId", it) }
            put("anonymousId", snap.anonymousId)
        }
        val outcome = httpRequest(
            method = "GET",
            path = "/entitlements",
            query = query,
        )
        val responseBody = outcome.envelope?.body
        if (outcome.kind != HttpSendOutcome.Kind.SUCCESS || responseBody.isNullOrEmpty()) {
            entitlements.markRefreshFailed()
            throw outcome.error ?: CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "get_entitlements_failed",
                message = "/entitlements did not succeed.",
            )
        }
        val response = decodeEntitlementsList(responseBody)
        identity.setCrossdeckCustomerId(response.crossdeckCustomerId)
        entitlements.write(
            EntitlementSnapshot(
                developerUserId = userId,
                entitlements = response.data,
            ),
        )
        return response.data
    }

    /**
     * Subscribe to entitlement-cache mutations. Returns an
     * unsubscriber; invoke it to detach.
     */
    public fun onEntitlementsChange(handler: EntitlementSubscriber): () -> Unit =
        entitlements.subscribe(handler)

    /**
     * Synchronous entitlement check. Safe to call from any thread.
     * Returns `false` if no user is identified OR the cache has
     * nothing for the current user (treat as "not yet known" —
     * fall back to [getEntitlements] + paywall if needed).
     */
    public fun isEntitled(key: String): Boolean {
        val userId = identity.snapshot().developerUserId ?: return false
        val answer = entitlements.isEntitled(key, userId)
        options.debugLogger(
            DebugSignal.SDK_ENTITLEMENT_CACHE_USED,
            mapOf("key" to key, "answer" to answer.toString()),
        )
        return answer
    }

    /**
     * Sync read of the full entitlement set for the current user.
     * Returns null if no user is identified or the cache is cold.
     */
    public fun entitlementsForCurrentCustomer(): List<PublicEntitlement>? {
        val userId = identity.snapshot().developerUserId ?: return null
        return entitlements.entitlementsFor(userId)
    }

    /** Active entitlement keys — convenience for paywall UIs. */
    public fun activeEntitlementKeys(): List<String>? =
        entitlementsForCurrentCustomer()?.filter { it.isActive }?.map { it.key }

    // ===============================================================
    // Public API — heartbeat
    // ===============================================================

    /**
     * Boot heartbeat. `GET /sdk/heartbeat` so the dashboard
     * onboarding checklist flips LIVE within ~200ms and so we
     * capture server-time for clock-skew detection. Auto-called
     * on `start(...)`; manual calls are still useful for "I'm
     * alive" pings during long sessions.
     *
     * Returns null on network failure — heartbeat is best-effort
     * and never throws.
     */
    public suspend fun heartbeat(): HeartbeatResponse? = heartbeatInternal()

    private suspend fun heartbeatInternal(): HeartbeatResponse? {
        val outcome = httpRequest(method = "GET", path = "/sdk/heartbeat")
        val body = outcome.envelope?.body
        if (outcome.kind != HttpSendOutcome.Kind.SUCCESS || body.isNullOrEmpty()) return null
        return runCatching { decodeHeartbeat(body) }.getOrNull()
    }

    // ===============================================================
    // Public API — state mutators
    // ===============================================================

    public fun reset() {
        assertStarted()
        identity.reset()
        // Logout-grade wipe: removes EVERY per-user entitlement
        // slot on this device (v1.4.x bank-grade isolation —
        // matches Web/RN reset() semantics). A shared device
        // can never leave another user's entitlements readable
        // after a logout.
        entitlements.clearAll()
        superProperties.clear()
        breadcrumbs.clear()
        options.debugLogger(DebugSignal.SDK_CONFIGURED, emptyMap())
    }

    public fun registerSuperProperty(key: String, value: String): Unit =
        superProperties.register(key, value)

    public fun registerSuperPropertyOnce(key: String, value: String): Unit =
        superProperties.registerOnce(key, value)

    public fun unregisterSuperProperty(key: String): Unit =
        superProperties.unregister(key)

    public fun addBreadcrumb(crumb: Breadcrumb): Unit = breadcrumbs.add(crumb)

    public fun captureError(throwable: Throwable, handled: Boolean = true) {
        ErrorCapture.shared.captureError(throwable, handled)
    }

    /**
     * Capture a handled message (no underlying [Throwable]). Used
     * for "this shouldn't have happened" log lines you want to
     * land on the dashboard. Goes through the same pipeline as
     * [captureError] but with no stack.
     */
    public fun captureMessage(message: String, level: BreadcrumbLevel = BreadcrumbLevel.INFO) {
        val synthetic = CrossdeckError(
            type = CrossdeckErrorType.INTERNAL_ERROR,
            code = "captured_message",
            message = message,
        )
        ErrorCapture.shared.captureError(synthetic, handled = true)
        breadcrumbs.add(
            Breadcrumb(
                category = BreadcrumbCategory.CUSTOM,
                level = level,
                message = message,
            ),
        )
    }

    /**
     * Set a single tag — attached to every subsequent error event
     * until cleared or overwritten. Tags are Sentry-style search
     * facets (`tag:plan=pro` in the dashboard).
     */
    public fun setTag(key: String, value: String) {
        if (key.isEmpty()) return
        synchronized(errorStateLock) { errorTags[key] = value }
    }

    /** Bulk tag setter — replaces the entire tag map atomically. */
    public fun setTags(tags: Map<String, String>) {
        synchronized(errorStateLock) {
            errorTags = tags.toMutableMap()
        }
    }

    /**
     * Set a named context block (Sentry-style). Pass an empty map
     * to clear a block.
     *
     * Stores a defensive copy of the map — a caller mutating the
     * map they passed in must NOT retroactively change what an
     * already-captured error reports. Matches Web/Node/RN/Swift.
     */
    public fun setContext(name: String, data: Map<String, String>) {
        if (name.isEmpty()) return
        synchronized(errorStateLock) {
            if (data.isEmpty()) {
                errorContext.remove(name)
            } else {
                errorContext[name] = data.toMap()
            }
        }
    }

    /**
     * Replace the `beforeSendError` hook at runtime. The hook
     * installed via [CrossdeckOptions.beforeSendError] is the
     * initial value; this lets a consumer rotate it after start
     * (e.g. install a stricter filter once consent changes). Pass
     * null to remove.
     */
    public fun setErrorBeforeSend(handler: BeforeSendErrorHandler?) {
        synchronized(errorStateLock) { runtimeBeforeSend = handler }
    }

    public fun setConsent(state: ConsentState) {
        consent.update(state)
        options.debugLogger(
            DebugSignal.SDK_CONSENT_CHANGED,
            mapOf(
                "analytics" to state.analytics.toString(),
                "errors" to state.errors.toString(),
            ),
        )
    }

    public fun setScrubPii(enabled: Boolean): Unit = consent.setScrubPii(enabled)

    /**
     * Read the current consent state. Mirrors Web SDK's
     * `consentStatus()` (v1.2.0 parity). Use this to render an
     * opt-out toggle in the consumer's settings UI without
     * tracking the consent dialog itself as a separate signal.
     */
    public fun consentStatus(): ConsentState = consent.state

    /**
     * Attach a B2B "group" / account context to every subsequent
     * event in the session — Mixpanel-style group analytics so
     * dashboards can aggregate by tenant / workspace / organisation.
     * Mirrors Web SDK's `cd.group(...)`.
     *
     * Setting `groupKey = null` clears the group context.
     */
    public fun group(groupType: String, groupKey: String?, traits: Map<String, Any?>? = null) {
        if (groupType.isEmpty()) return
        // Set as a super-property so every subsequent event ships
        // the group key — same model as Web SDK.
        if (groupKey == null) {
            superProperties.unregister("group_${groupType}")
        } else {
            superProperties.register("group_${groupType}", groupKey)
        }
        // Fire an alias event so the backend can project the
        // group→user relationship.
        val props = mutableMapOf<String, Any?>(
            "groupType" to groupType,
        )
        if (groupKey != null) props["groupKey"] = groupKey
        traits?.let { props["traits"] = it }
        try {
            track("group.set", props)
        } catch (_: CrossdeckError) {
            // group() must not throw — Web/Node/RN don't.
        }
    }

    /**
     * Force a new session immediately — ends the current session
     * (if active) and mints a fresh `sessionId`. Useful from
     * logout flows where you want the dashboard to see a clean
     * boundary between the previous user's activity and the next.
     *
     * Auto-track sessions still fire on app foreground / background
     * cycles regardless of this call.
     */
    public fun resetSession(): Unit = AutoTracker.shared.resetSession()

    // ===============================================================
    // Public API — queue control
    // ===============================================================

    public suspend fun flush(): Unit = queue.flush()

    public suspend fun stats(): QueueStats = queue.stats()

    /**
     * Persist pending state to storage + stop background work.
     * Idempotent — second call is a no-op. After `stop()`,
     * mutating API calls throw `not_initialized`.
     *
     * **Bank-grade durability contract:** this method blocks the
     * caller while pending events are flushed to disk. Disk IO
     * is bounded (~ms on SharedPreferences); skipping the wait
     * would race the subsequent `scope.cancel()` and silently
     * drop the in-flight persist.
     */
    public fun stop() {
        synchronized(startedLock) {
            if (!started) return
            started = false
        }
        // 1) Cancel the periodic flush + lifecycle hook so they
        //    don't enqueue new work mid-teardown.
        flushTickerJob?.cancel()
        unregisterLifecycleObservers()

        // 1b) Tear down v1.2.0 ambient modules. AutoTracker's global
        //     observers stay installed (un-registering is race-prone),
        //     but this instance's listener unregisters so no more
        //     events flow to it.
        autoTrackUnregister?.invoke()
        autoTrackUnregister = null
        reachability?.stop()
        reachability = null
        performanceVitals?.stop()
        performanceVitals = null
        crashOnRelaunch = null

        // 2) Persist pending state SYNCHRONOUSLY before tearing
        //    down the scope. runBlocking on IO is correct here —
        //    stop() is a shutdown path, the caller already accepts
        //    a blocking call, and the alternative (`scope.launch`
        //    followed by `scope.cancel`) would cancel the persist
        //    coroutine mid-write. Bank-grade rule: durability
        //    wins over latency on the teardown path.
        runBlocking(Dispatchers.IO) { queue.persistAll() }

        // 3) Tear down error routing for THIS client. The JVM-level
        //    UncaughtExceptionHandler stays installed (Android offers
        //    no clean removal path); a future Crossdeck.start
        //    replaces our routing closure.
        ErrorCapture.shared.uninstall()

        // 4) Cancel the main scope so any post-teardown launches
        //    (`scope.launch { ... }` in setTag-style mutators
        //    that already throw `not_initialized`) are no-ops
        //    instead of resource leaks. Multiple start/stop
        //    cycles without this leak a SupervisorJob +
        //    dispatcher reference per cycle.
        scope.cancel()
        options.debugLogger(DebugSignal.SDK_CONFIGURED, mapOf("stopped" to "true"))
    }

    // ===============================================================
    // Internal helpers for BillingAutoTrack.kt
    // ===============================================================

    /**
     * Submit a [BillingPurchase] to the backend for cryptographic
     * verification + entitlement projection. Returns a typed
     * [Result] so the caller observes failure — silent swallow on
     * a money-path call is forbidden by the bank-grade contract.
     *
     * Same backend contract `syncPurchases()` uses — single contract
     * surface, no shape drift between auto and manual paths.
     *
     * `rail = "google"` matches the canonical wire token in
     * `backend/src/lib/types.ts` (`PaymentRail = "apple" | "stripe"
     * | "google"`). The /purchases/sync endpoint currently returns
     * 501 google_not_supported until the Play Developer API
     * reconciliation worker lights up; the typed failure flows
     * through unchanged when that happens.
     */
    internal suspend fun syncBillingPurchase(purchase: BillingPurchase): Result<Unit> {
        return try {
            val body = JSONObject().apply {
                put("rail", "google")
                put("productId", purchase.productId)
                put("purchaseToken", purchase.purchaseToken)
                put("purchaseTimeMillis", purchase.purchaseTimeMillis)
                purchase.orderId?.let { put("orderId", it) }
                purchase.signature?.let { put("signature", it) }
                put("isAcknowledged", purchase.isAcknowledged)
            }.toString().toByteArray(Charsets.UTF_8)

            // Phase 2.2.c — deterministic Idempotency-Key from the
            // Google purchaseToken so a retry (network blip, Play
            // re-delivery, app-crash mid-flight) lands on the same
            // key + the backend short-circuits with
            // idempotent_replay:true. Falls back to a fresh random
            // only when purchaseToken is somehow absent (defensive
            // — auto-track always supplies one).
            val idempotencyKey = IdempotencyKey.deriveForPurchase(
                rail = "google",
                purchaseToken = purchase.purchaseToken,
            ) ?: ("auto_purch_" + java.util.UUID.randomUUID()
                .toString()
                .lowercase()
                .replace("-", ""))
            val outcome = httpRequest(
                method = "POST",
                path = "/purchases/sync",
                body = body,
                idempotencyKey = idempotencyKey,
            )
            mapBillingSyncOutcome(outcome)
        } catch (t: Throwable) {
            Result.failure(
                CrossdeckError(
                    type = CrossdeckErrorType.INTERNAL_ERROR,
                    code = "auto_billing_sync_threw",
                    message = t.message ?: "Auto-billing sync threw an exception.",
                    cause = t,
                ),
            )
        }
    }

    /**
     * Fire-and-forget wrapper used by [handleBillingResult] in
     * BillingAutoTrack.kt — Play Billing's `PurchasesUpdatedListener`
     * is a non-suspending callback so the wrapper launches the
     * suspending [syncBillingPurchase] on the SDK scope.
     *
     * Failures are surfaced via THREE independent channels (defense-
     * in-depth — founder principle 2):
     *   1. Optional [onResult] callback — programmatic consumer hook.
     *   2. `purchase.sync_failed` analytics event with typed fields
     *      (errorType, errorCode, statusCode, productId) — visible
     *      in dashboards / funnels even when the consumer doesn't
     *      register a callback.
     *   3. `options.debugLogger` with the full typed envelope —
     *      visible in dev-mode logs.
     */
    internal fun syncBillingPurchaseAsync(
        purchase: BillingPurchase,
        onResult: ((Result<Unit>) -> Unit)? = null,
    ) {
        scope.launch {
            val result = syncBillingPurchase(purchase)
            result.onFailure { err ->
                val typed = err as? CrossdeckError
                val errorType = typed?.type?.wireValue ?: "unknown_error"
                val errorCode = typed?.code ?: "auto_billing_sync_threw"
                val statusCode = typed?.statusCode
                val requestId = typed?.requestId

                options.debugLogger(
                    DebugSignal.SDK_CONFIGURED,
                    buildMap {
                        put("auto_billing_sync_failed", "true")
                        put("error_type", errorType)
                        put("error_code", errorCode)
                        if (statusCode != null) put("status_code", statusCode.toString())
                        if (requestId != null) put("request_id", requestId)
                        put("product_id", purchase.productId)
                    },
                )

                // Surface the failure as a track-able funnel event so
                // it's visible in dashboards even without a debug
                // logger configured. Best-effort: if the SDK has
                // been stopped between launch + here, swallow the
                // assertStarted() throw — we've already logged.
                try {
                    val props = buildMap<String, Any?> {
                        put("rail", "google")
                        put("productId", purchase.productId)
                        put("purchaseToken", purchase.purchaseToken)
                        purchase.orderId?.let { put("orderId", it) }
                        put("errorType", errorType)
                        put("errorCode", errorCode)
                        if (statusCode != null) put("statusCode", statusCode)
                        if (requestId != null) put("requestId", requestId)
                    }
                    track("purchase.sync_failed", props)
                } catch (_: Throwable) {
                }
            }
            onResult?.invoke(result)
        }
    }

    // ===============================================================
    // Internals — assertions, snapshots, helpers
    // ===============================================================

    private fun assertStarted() {
        synchronized(startedLock) {
            if (!started) {
                throw CrossdeckError(
                    type = CrossdeckErrorType.INVALID_REQUEST,
                    code = "not_initialized",
                    message = "Crossdeck client was stopped — call Crossdeck.start(...) again.",
                )
            }
        }
    }

    private fun sanitiseTraits(
        traits: Map<String, Any?>?,
        scope: String,
    ): Map<String, Any?> {
        if (traits == null) return emptyMap()
        val result = validateEventProperties(traits)
        for (warning in result.warnings) {
            options.debugLogger(
                DebugSignal.SDK_PROPERTY_COERCED,
                mapOf(
                    "scope" to scope,
                    "key" to warning.key,
                    "kind" to warning.kind.wireValue,
                ),
            )
        }
        return result.properties
    }

    /**
     * Convert traits (Any-valued) to the wire shape (String-keyed
     * String values). The backend `/identity/alias` accepts a
     * `Record<string, string>` here; complex values get
     * `toString()`-coerced rather than crashing the encoder.
     */
    private fun wireTraits(cleaned: Map<String, Any?>): Map<String, String>? {
        if (cleaned.isEmpty()) return null
        val out = LinkedHashMap<String, String>(cleaned.size)
        for ((k, v) in cleaned) {
            // Wire format is Record<string, string> — drop nulls
            // rather than stringifying them to "null" (matches
            // Web/Node/RN/Swift; backend rejects unknown shapes).
            when (v) {
                null -> continue
                is String -> out[k] = v
                else -> out[k] = v.toString()
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Snapshot the runtime error state. Used by the ErrorCapture
     * install closure when building a wire event so a setTag /
     * setContext / setErrorBeforeSend call after install takes
     * effect for the NEXT error.
     */
    private fun snapshotErrorState(): ErrorState = synchronized(errorStateLock) {
        ErrorState(
            tags = errorTags.toMap(),
            context = errorContext.toMap(),
            beforeSend = runtimeBeforeSend,
        )
    }

    private data class ErrorState(
        val tags: Map<String, String>,
        val context: Map<String, Map<String, String>>,
        val beforeSend: BeforeSendErrorHandler?,
    )

    // ---------------------------------------------------------------
    // Error capture wiring
    // ---------------------------------------------------------------

    private fun installErrorCapture() {
        // Seed the runtime hook with the option-provided value so
        // a consumer's CrossdeckOptions.beforeSendError is the
        // initial value of the replaceable hook.
        options.beforeSendError?.let { setErrorBeforeSend(it) }

        // Bind the fatal-persist hook to the CrashOnRelaunch
        // persister (when enabled). On fatal, write the throwable
        // to disk SYNCHRONOUSLY before the rest of the uncaught-
        // handler chain runs — guarantees the event survives
        // process death even if the queue's enqueue races the
        // OS killing us.
        val persistFatal: ((Throwable) -> Unit)? = crashOnRelaunch?.let { persister ->
            { throwable ->
                val sessionIdSnap = AutoTracker.shared.currentSessionId()
                val idSnap = identity.snapshot()
                persister.writeFatal(throwable, sessionIdSnap, idSnap.developerUserId)
            }
        }

        ErrorCapture.shared.install(
            beforeSend = null, // runtime hook applied inside capture closure
            breadcrumbs = { breadcrumbs.snapshot() },
            selfHostname = selfHostname,
            installGlobalHandler = options.captureUncaughtExceptions,
            onFatal = persistFatal,
            capture = { event ->
                // Errors-consent gate. Sync read so we don't pay
                // an indirection on the crash path.
                val consentSnap = consent.state
                if (!consentSnap.errors) {
                    options.debugLogger(
                        DebugSignal.SDK_CONSENT_DENIED,
                        mapOf("channel" to "errors", "type" to event.type),
                    )
                    return@install
                }

                val idSnap = identity.snapshot()
                val scrub = consent.scrubPiiEnabled
                val scrubbedMessage = if (scrub) scrubPii(event.message) else event.message
                val stackStrings: List<String> = event.stack.map { f ->
                    val raw = "${f.module}:${f.symbol}"
                    if (scrub) scrubPii(raw) else raw
                }

                val props = LinkedHashMap<String, Any?>(8)
                props["error.type"] = event.type
                props["error.message"] = scrubbedMessage
                props["error.fingerprint"] = event.fingerprint
                props["error.handled"] = event.handled
                props["error.timestamp_ms"] = event.timestampMs

                // Runtime tags + context. Mirrors Web/Node/RN/Swift
                // — dashboard surfaces these as search facets.
                val errState = snapshotErrorState()
                if (errState.tags.isNotEmpty()) props["error.tags"] = errState.tags
                if (errState.context.isNotEmpty()) props["error.context"] = errState.context
                if (stackStrings.isNotEmpty()) props["error.stack"] = stackStrings

                if (event.breadcrumbs.isNotEmpty()) {
                    props["error.breadcrumbs"] = event.breadcrumbs.map { crumb ->
                        val dict = LinkedHashMap<String, Any?>(5)
                        dict["timestamp_ms"] = crumb.timestampMs
                        dict["category"] = crumb.category.wireValue
                        dict["level"] = crumb.level.wireValue
                        dict["message"] = if (scrub) scrubPii(crumb.message) else crumb.message
                        crumb.data?.takeIf { it.isNotEmpty() }?.let { data ->
                            dict["data"] = if (scrub) {
                                data.mapValues { scrubPii(it.value) }
                            } else {
                                data
                            }
                        }
                        dict
                    }
                }

                // Runtime beforeSend — returning null drops the
                // error. Matches Web/Node/RN/Swift.
                val hook = errState.beforeSend
                if (hook != null && hook(event) == null) {
                    options.debugLogger(
                        DebugSignal.SDK_CONSENT_DENIED,
                        mapOf(
                            "channel" to "errors",
                            "reason" to "beforeSend_returned_nil",
                        ),
                    )
                    return@install
                }

                val wire = WireEvent(
                    id = "err_" + UUID.randomUUID().toString().lowercase().replace("-", ""),
                    name = "\$error",
                    timestampMs = event.timestampMs,
                    properties = props,
                    anonymousId = idSnap.anonymousId,
                    developerUserId = idSnap.developerUserId,
                    crossdeckCustomerId = idSnap.crossdeckCustomerId,
                )
                scope.launch { queue.enqueue(wire) }
            },
        )
    }

    // ---------------------------------------------------------------
    // Lifecycle observers — flush + persist on app-background
    // ---------------------------------------------------------------

    private fun installLifecycleObservers(appCtx: Context) {
        val app = appCtx as? Application ?: return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                // Best-effort flush + persist. Android gives a few
                // seconds of background time before suspension —
                // enough to ship a small batch and ALWAYS enough
                // to fsync the queue so we don't drop on power
                // loss / OOM-kill.
                scope.launch {
                    queue.persistAll()
                    queue.flush()
                }
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        this.application = app
        this.lifecycleCallbacks = callbacks
    }

    private fun unregisterLifecycleObservers() {
        val cb = lifecycleCallbacks ?: return
        application?.unregisterActivityLifecycleCallbacks(cb)
        lifecycleCallbacks = null
        application = null
    }

    /**
     * Periodic flush coroutine. Fires every [EventQueueConfig.flushIntervalMs]
     * regardless of buffer size — matches Web/Node/RN/Swift so an
     * idle app still ships its event every few seconds rather than
     * holding events until the buffer hits batch size.
     */
    private fun startFlushTicker() {
        flushTickerJob?.cancel()
        flushTickerJob = scope.launch {
            val interval = options.queueConfig.flushIntervalMs.coerceAtLeast(250L)
            while (isActive) {
                delay(interval)
                queue.flush()
            }
        }
    }

    // ---------------------------------------------------------------
    // HTTP helpers — every endpoint flows through `httpRequest`
    // so the IO offload + idempotency-key conventions stay in one
    // place. Public `Http.request` is blocking; we always wrap it
    // in withContext(Dispatchers.IO).
    // ---------------------------------------------------------------

    private suspend fun httpRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        query: Map<String, String>? = null,
        idempotencyKey: String? = null,
    ): HttpSendOutcome = withContext(Dispatchers.IO) {
        http.request(method, path, body, query, idempotencyKey)
    }

    private suspend fun postAliasIdentity(req: AliasIdentityRequest): AliasResult {
        val outcome = httpRequest(
            method = "POST",
            path = "/identity/alias",
            body = encodeJsonBody(req),
            idempotencyKey = makeIdempotencyKey(),
        )
        val body = outcome.envelope?.body
        if (outcome.kind != HttpSendOutcome.Kind.SUCCESS || body.isNullOrEmpty()) {
            throw outcome.error ?: CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "alias_failed",
                message = "/identity/alias did not succeed.",
            )
        }
        return decodeAliasResult(body)
    }

    // ---------------------------------------------------------------
    // JSON encoders / decoders. No third-party dep — org.json ships
    // with Android. The encoders match the backend validator field
    // names exactly (ServerEndpoints.kt documents the source of
    // truth).
    // ---------------------------------------------------------------

    private fun encodeJsonBody(req: AliasIdentityRequest): ByteArray {
        val o = JSONObject()
        o.put("userId", req.userId)
        o.put("anonymousId", req.anonymousId)
        req.email?.let { o.put("email", it) }
        if (!req.traits.isNullOrEmpty()) {
            val t = JSONObject()
            for ((k, v) in req.traits) t.put(k, v)
            o.put("traits", t)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeJsonBody(req: ForgetIdentityRequest): ByteArray {
        val o = JSONObject()
        req.userId?.let { o.put("userId", it) }
        req.anonymousId?.let { o.put("anonymousId", it) }
        req.customerId?.let { o.put("customerId", it) }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeJsonBody(req: PurchaseSyncRequest): ByteArray {
        val o = JSONObject()
        o.put("rail", req.rail)
        req.signedTransactionInfo?.let { o.put("signedTransactionInfo", it) }
        req.signedRenewalInfo?.let { o.put("signedRenewalInfo", it) }
        req.purchaseToken?.let { o.put("purchaseToken", it) }
        req.appAccountToken?.let { o.put("appAccountToken", it) }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeAliasResult(body: String): AliasResult {
        val root = JSONObject(body)
        val crossdeckCustomerId = root.optString("crossdeckCustomerId").takeIf { it.isNotEmpty() }
            ?: throw CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "alias_response_invalid",
                message = "/identity/alias response missing crossdeckCustomerId.",
            )
        val mergePending = root.optBoolean("mergePending", false)
        val env = Environment.fromWire(root.optString("env").takeIf { it.isNotEmpty() })
            ?: options.environment
        val linkedArr = root.optJSONArray("linked")
        val linked = if (linkedArr != null) {
            ArrayList<AliasResult.LinkedIdentity>(linkedArr.length()).apply {
                for (i in 0 until linkedArr.length()) {
                    val item = linkedArr.optJSONObject(i) ?: continue
                    val type = item.optString("type").takeIf { it.isNotEmpty() } ?: continue
                    val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    add(AliasResult.LinkedIdentity(type, id))
                }
            }
        } else {
            emptyList()
        }
        return AliasResult(
            crossdeckCustomerId = crossdeckCustomerId,
            mergePending = mergePending,
            env = env,
            linked = linked,
        )
    }

    private fun decodeEntitlementsList(body: String): EntitlementsListResponse {
        val root = JSONObject(body)
        val crossdeckCustomerId = root.optString("crossdeckCustomerId").takeIf { it.isNotEmpty() }
            ?: throw CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "entitlements_response_invalid",
                message = "/entitlements response missing crossdeckCustomerId.",
            )
        val env = Environment.fromWire(root.optString("env").takeIf { it.isNotEmpty() })
            ?: options.environment
        val arr = root.optJSONArray("data") ?: JSONArray()
        val list = ArrayList<PublicEntitlement>(arr.length())
        for (i in 0 until arr.length()) {
            decodePublicEntitlement(arr.optJSONObject(i))?.let { list.add(it) }
        }
        return EntitlementsListResponse(
            data = list,
            crossdeckCustomerId = crossdeckCustomerId,
            env = env,
        )
    }

    private fun decodePurchaseResult(body: String): PurchaseResult {
        val root = JSONObject(body)
        val crossdeckCustomerId = root.optString("crossdeckCustomerId").takeIf { it.isNotEmpty() }
            ?: throw CrossdeckError(
                type = CrossdeckErrorType.INTERNAL_ERROR,
                code = "purchase_response_invalid",
                message = "/purchases/sync response missing crossdeckCustomerId.",
            )
        val env = Environment.fromWire(root.optString("env").takeIf { it.isNotEmpty() })
            ?: options.environment
        val arr = root.optJSONArray("entitlements") ?: JSONArray()
        val list = ArrayList<PublicEntitlement>(arr.length())
        for (i in 0 until arr.length()) {
            decodePublicEntitlement(arr.optJSONObject(i))?.let { list.add(it) }
        }
        return PurchaseResult(
            crossdeckCustomerId = crossdeckCustomerId,
            env = env,
            entitlements = list,
        )
    }

    private fun decodeHeartbeat(body: String): HeartbeatResponse {
        val root = JSONObject(body)
        val ok = root.optBoolean("ok", false)
        val projectId = root.optString("projectId")
        val appId = root.optString("appId")
        val env = Environment.fromWire(root.optString("env").takeIf { it.isNotEmpty() })
            ?: options.environment
        val serverTime = root.optLong("serverTime", System.currentTimeMillis())
        return HeartbeatResponse(
            ok = ok,
            projectId = projectId,
            appId = appId,
            env = env,
            serverTime = serverTime,
        )
    }

    private fun decodePublicEntitlement(o: JSONObject?): PublicEntitlement? {
        // Strictness matches EntitlementCache.decodeEntitlement —
        // missing `source` or unknown `rail` drops the entitlement
        // rather than fabricating a synthetic source. Backend
        // /entitlements responses always include source, so this
        // only fires on a malformed payload.
        if (o == null) return null
        val key = o.optString("key").takeIf { it.isNotEmpty() } ?: return null
        val sourceObj = o.optJSONObject("source") ?: return null
        val rail = AuditRail.fromWire(sourceObj.optString("rail")) ?: return null
        val validUntil = if (o.has("validUntil") && !o.isNull("validUntil")) {
            o.optLong("validUntil")
        } else {
            null
        }
        return PublicEntitlement(
            key = key,
            isActive = o.optBoolean("isActive", false),
            validUntil = validUntil,
            source = PublicEntitlement.EntitlementSource(
                rail = rail,
                productId = sourceObj.optString("productId"),
                subscriptionId = sourceObj.optString("subscriptionId"),
            ),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    // ---------------------------------------------------------------
    // ID generators — formats match the backend validators.
    // ---------------------------------------------------------------

    private fun makeEventId(): String =
        "evt_" + UUID.randomUUID().toString().lowercase().replace("-", "")

    private fun makeIdempotencyKey(): String =
        "batch_" + UUID.randomUUID().toString().lowercase().replace("-", "")
}
