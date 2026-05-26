// Process-global uncaught exception capture.
//
// Android's `Thread.setDefaultUncaughtExceptionHandler` is
// process-wide — there is exactly one slot. To coexist with other
// crash reporters (Firebase Crashlytics, Sentry, Bugsnag) we MUST:
//
//   * Capture the existing handler at install time
//   * Forward to it after our own snapshot is taken
//
// Skipping the chain would silently break every other crash
// reporter on the device. Bank-grade contract: be a polite
// citizen on a shared global.
//
// The Crossdeck client routes through the singleton; only one
// instance can be the active publisher at a time, but the
// singleton itself is shared. Mirrors Swift `ErrorCapture.shared`.

package com.crossdeck

public typealias BeforeSendErrorHandler = (event: CapturedError) -> CapturedError?

public data class CapturedError(
    public val type: String,
    public val message: String,
    public val fingerprint: String,
    public val stack: List<ParsedStackFrame>,
    public val breadcrumbs: List<Breadcrumb>,
    public val timestampMs: Long = System.currentTimeMillis(),
    public val handled: Boolean,
)

/**
 * Process-wide error-capture coordinator. Singleton-shaped because
 * `Thread.setDefaultUncaughtExceptionHandler` is process-global.
 *
 * Lifecycle:
 *
 *   1. `Crossdeck.start(...)` calls [install] with a routing
 *      closure that builds the wire event + enqueues it.
 *   2. Each `captureError(throwable, handled)` call snapshots
 *      breadcrumbs + identity + applies the consumer's
 *      `beforeSend` hook, then routes through the closure.
 *   3. On uncaught exceptions, the JVM calls our
 *      `UncaughtExceptionHandler` — we capture, then forward to
 *      the prior handler so Crashlytics + Sentry still run.
 *
 * Idempotent. Calling [install] twice replaces the routing but
 * does NOT re-register the JVM hook (would otherwise chain a new
 * prior-handler reference each time and leak forwarders).
 */
public class ErrorCapture private constructor() {

    public companion object {
        /** Process-wide singleton. */
        @JvmStatic
        public val shared: ErrorCapture = ErrorCapture()
    }

    private val lock = Any()

    private var beforeSend: BeforeSendErrorHandler? = null
    private var captureHandler: ((CapturedError) -> Unit)? = null
    private var breadcrumbsSnapshot: (() -> List<Breadcrumb>)? = null
    private var selfHostname: String? = null
    private var installed: Boolean = false

    /**
     * The JVM-level handler that was registered before we installed
     * ours (Crashlytics / Sentry / etc.). We forward to it after
     * our own capture completes so the other reporter keeps
     * working — silently swallowing it would be a bank-grade
     * regression for any consumer wiring both libraries.
     */
    private var priorHandler: Thread.UncaughtExceptionHandler? = null
    private var onFatalHook: ((Throwable) -> Unit)? = null

    /**
     * Install the JVM uncaught handler + route subsequent captures
     * through [capture]. Idempotent — repeat calls update the
     * routing closure without re-chaining the prior handler.
     */
    public fun install(
        beforeSend: BeforeSendErrorHandler?,
        breadcrumbs: () -> List<Breadcrumb>,
        selfHostname: String?,
        capture: (CapturedError) -> Unit,
        installGlobalHandler: Boolean,
        onFatal: ((Throwable) -> Unit)? = null,
    ) {
        synchronized(lock) {
            this.beforeSend = beforeSend
            this.captureHandler = capture
            this.breadcrumbsSnapshot = breadcrumbs
            this.selfHostname = selfHostname
            this.onFatalHook = onFatal

            // ALWAYS wire the routing closure so cd.captureError(...)
            // works on every project. The global JVM handler is gated
            // separately by [installGlobalHandler] so consumers running
            // Firebase Crashlytics / Sentry / Bugsnag as their primary
            // crash reporter can opt out of our global hook without
            // losing manual capture from do/catch / runCatching paths.
            if (!installGlobalHandler) return@synchronized
            if (installed) return@synchronized
            installed = true

            // Capture and chain the prior handler. Reading the
            // existing default BEFORE setting ours is the only
            // safe order — set-then-read would always read our
            // own handler.
            priorHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // FIRST: synchronous fatal persist. This is the
                // crash-on-relaunch hook. Runs BEFORE capture+forward
                // so the disk write completes even if the rest of
                // the chain throws — bank-grade durability rule:
                // events that would otherwise vanish across process
                // death must hit the disk before we forward control.
                try {
                    onFatalHookSnapshot()?.invoke(throwable)
                } catch (_: Throwable) {
                }
                // Best-effort capture; never let our side-effect
                // throw, because the JVM is already on its way
                // out and a throw from here just hides the
                // original crash.
                try {
                    shared.captureFromGlobalHandler(throwable)
                } catch (_: Throwable) {
                    // Swallow — original crash takes priority.
                }
                // Forward to the prior handler (Crashlytics etc.)
                // so the rest of the dev's reporting stack still
                // fires. If there was no prior handler, the JVM's
                // default behaviour (print + terminate) takes over
                // — we don't `System.exit` ourselves.
                priorHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun onFatalHookSnapshot(): ((Throwable) -> Unit)? =
        synchronized(lock) { onFatalHook }

    /**
     * Stop routing — used when the active [Crossdeck] is calling
     * `stop()`. The JVM-level hook stays installed (Android offers
     * no clean removal path; subsequent uncaught throws fall
     * through to the chained prior handler). Routing the next
     * uncaught throw to a missing capture closure is a no-op.
     */
    public fun uninstall() {
        synchronized(lock) {
            this.beforeSend = null
            this.captureHandler = null
            this.breadcrumbsSnapshot = null
            this.selfHostname = null
        }
    }

    /**
     * Manual capture path for handled errors (try/catch flows).
     * Skipped silently when [Crossdeck] is in a stopped state
     * (capture closure is nil) or when the throwable is a
     * self-request error (would feed a loop).
     */
    public fun captureError(throwable: Throwable, handled: Boolean = true) {
        val routing = synchronized(lock) {
            Routing(
                capture = captureHandler,
                breadcrumbs = breadcrumbsSnapshot,
                selfHostname = selfHostname,
                beforeSend = beforeSend,
            )
        }
        val capture = routing.capture ?: return

        // Self-request skip: errors thrown while the SDK is
        // trying to ship its own events should never be reported.
        // Avoids the classic "logging your logger's failures into
        // your logger" feedback loop. The hostname pivot is the
        // configured baseUrl (so a staging or self-hosted relay
        // works correctly), not the production default.
        if (errorMatchesSelfHost(throwable, routing.selfHostname)) return

        val event = buildCapturedError(throwable, handled, routing.breadcrumbs)

        // beforeSend hook — last chance for the consumer to scrub
        // or drop the error. Returning null drops it; modified
        // return value is what ships.
        val hook = routing.beforeSend
        val final = if (hook != null) hook(event) else event
        if (final != null) capture(final)
    }

    /**
     * Used by the JVM uncaught handler. Behaves like
     * [captureError] with `handled = false`, but never returns
     * (the JVM is on its way out).
     */
    private fun captureFromGlobalHandler(throwable: Throwable) {
        captureError(throwable, handled = false)
    }

    private fun buildCapturedError(
        throwable: Throwable,
        handled: Boolean,
        breadcrumbsProvider: (() -> List<Breadcrumb>)?,
    ): CapturedError {
        val (typeName, message) = errorTypeAndMessage(throwable)
        val frames = parseStackTrace(throwable.stackTrace)
        val fingerprint = fingerprintFromStack(throwable)
        val crumbs = breadcrumbsProvider?.invoke() ?: emptyList()
        return CapturedError(
            type = typeName,
            message = message,
            fingerprint = fingerprint,
            stack = frames,
            breadcrumbs = crumbs,
            handled = handled,
        )
    }

    private data class Routing(
        val capture: ((CapturedError) -> Unit)?,
        val breadcrumbs: (() -> List<Breadcrumb>)?,
        val selfHostname: String?,
        val beforeSend: BeforeSendErrorHandler?,
    )
}

private fun errorTypeAndMessage(throwable: Throwable): Pair<String, String> {
    return when (throwable) {
        is CrossdeckError -> "CrossdeckError.${throwable.type.wireValue}" to (throwable.message ?: "")
        else -> {
            val typeName = throwable::class.qualifiedName ?: throwable::class.java.name
            val msg = throwable.message ?: throwable.toString()
            typeName to msg
        }
    }
}

/**
 * Heuristic self-request matcher. Catches the two shapes the SDK
 * itself emits: a wrapped network error that contains the host as
 * its message, and a cause-chained throwable whose message names
 * the host. Avoids matching on type alone (the JVM doesn't have a
 * URL-error type the way Swift's `URLError` does) which would risk
 * dropping unrelated host-mentioning errors.
 */
private fun errorMatchesSelfHost(throwable: Throwable, host: String?): Boolean {
    if (host.isNullOrEmpty()) return false
    var current: Throwable? = throwable
    var hops = 0
    while (current != null && hops < 8) {
        val msg = current.message ?: ""
        if (msg.contains(host, ignoreCase = true)) return true
        current = current.cause
        hops += 1
    }
    return false
}
