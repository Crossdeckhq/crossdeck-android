// _DiagnosticTelemetry.kt
//
// Single-fire reliability telemetry for the SDK. Carries the
// `crossdeck.contract_failed` event ONE WAY to the Crossdeck
// reliability endpoint — NEVER the customer's appId, NEVER the
// customer's track() pipeline, NEVER visible in the customer's
// dashboard.
//
// Why this exists
// ──────────────────────────────────────────────────────────────────
// Crossdeck is an independent controller for SDK Diagnostic
// Telemetry (Privacy Policy §6, "Flow B"). The legitimate-interest
// basis depends on the payload remaining diagnostic-only: no
// end-user identifiers, no free-form text, no stack frames. The
// schema-lock contract at
// `contracts/diagnostics/contract-failed-payload-schema-lock.json`
// fixes the wire shape; this module is the call site that has to
// honour it.
//
// Why bypass the existing HttpClient
// ──────────────────────────────────────────────────────────────────
// The HttpClient is configured for the customer's project (their
// API key, their endpoint). Routing reliability telemetry through
// it would (a) bill against the customer's event quota and (b)
// show individual contract failures in their dashboard, which is
// neither the customer's nor Crossdeck's intent. A separate one-way
// path is the structural guarantee.
//
// PROVISIONING NOTE
// ──────────────────────────────────────────────────────────────────
// The reliability endpoint URL + publishable key below are LITERAL
// CONSTANTS shipped in the SDK. Until the reliability project is
// minted, the placeholder values disable telemetry — the function
// returns early without making a request. After provisioning, swap
// the placeholders for the real values; the same values go into the
// backend at backend/src/api/v1-sdk-diagnostic.ts.
package com.crossdeck

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal object _DiagnosticTelemetry {
    /**
     * The reliability endpoint URL. Hardcoded — the SDK never reads
     * this from configuration so a customer cannot accidentally
     * redirect diagnostic telemetry to their own project.
     */
    internal const val ENDPOINT_URL: String =
        "https://api.cross-deck.com/v1/sdk/diagnostic"

    /**
     * The reliability project's publishable key. Hardcoded for the
     * same reason. Replace at provisioning time.
     */
    internal const val PUBLISHABLE_KEY: String =
        "cd_pub_RELIABILITY_PLACEHOLDER_TO_BE_PROVISIONED"

    /**
     * Whether the telemetry is enabled. Disabled while the
     * reliability project is unprovisioned (placeholder key in
     * place). Reading this branch lets us merge + ship the
     * schema-lock + endpoint code before the reliability project
     * exists, without firing requests to the placeholder URL.
     */
    internal val isEnabled: Boolean
        get() = !PUBLISHABLE_KEY.startsWith("cd_pub_RELIABILITY_PLACEHOLDER")

    /**
     * The exhaustive set of fields the payload may contain — mirrors
     * the schema-lock contract. Anything outside this set is dropped
     * at the call site so a future caller can't accidentally widen
     * the wire shape.
     */
    internal val ALLOWED_KEYS: Set<String> = setOf(
        "contract_id",
        "sdk_version",
        "sdk_platform",
        "failure_reason",
        "run_context",
        "run_id",
        "test_file",
        "test_name",
        "device_class",
    )

    // Single-thread executor — reliability telemetry must NEVER block
    // the host app's main thread on an HTTP connect. We don't even
    // want to share the SDK's regular IO pool because retries from a
    // flaky CI run could starve customer-event delivery.
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "crossdeck-diagnostic-telemetry").apply { isDaemon = true }
        }
    }

    /**
     * Fire a `crossdeck.contract_failed` event over the reliability
     * channel. Returns immediately; the POST happens on a daemon
     * worker thread. Never throws — failures are silently dropped so
     * the customer's app is not affected by reliability-endpoint
     * availability.
     *
     * @param payload key/value map of payload fields. Keys not in
     *   [ALLOWED_KEYS] are dropped before serialisation.
     */
    @JvmStatic
    internal fun send(payload: Map<String, String>) {
        if (!isEnabled) return
        // Whitelist filter — even if a caller threads a forbidden key
        // (anonymousId, ip, etc.) through, it never hits the wire.
        // The backend would reject it anyway; this is defence in depth.
        val filtered = payload.filterKeys { ALLOWED_KEYS.contains(it) }
        if (filtered.isEmpty()) return
        val body = JSONObject(filtered).toString()

        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ENDPOINT_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    // Short timeouts — reliability telemetry must never
                    // stall. A failed POST is acceptable; a hung POST
                    // is not.
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $PUBLISHABLE_KEY")
                    setRequestProperty(
                        "Crossdeck-Sdk-Version",
                        "${Sdk.NAME}@${Sdk.VERSION}",
                    )
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                // Touch the response code to actually flush the
                // request — HttpURLConnection is lazy and won't send
                // until something reads. We ignore the value: there is
                // nothing actionable to do with a failed diagnostic
                // POST, and we never want to feed a retry loop that
                // could amplify a problem.
                @Suppress("UNUSED_VARIABLE")
                val ignored = conn.responseCode
            } catch (_: Throwable) {
                // Swallowed by design — see contract above.
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }
}
