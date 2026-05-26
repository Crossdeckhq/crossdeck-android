// Stripe-style structured error envelope.
//
// Every error the SDK surfaces — whether thrown out of a public
// API, raised inside the queue, or returned to a callback — uses
// this same shape. The four fields mirror exactly what the backend
// /v1/events endpoint returns in
// `{ error: { type, code, message, request_id } }`, so consumer
// handling code is identical for "server returned X" and "SDK
// refused to send your event" scenarios.

package com.crossdeck

import org.json.JSONObject

/**
 * Discriminator for the kind of failure that occurred. Mirrors
 * Stripe's `type` taxonomy — clients route on [type], message is
 * human-readable.
 */
public enum class CrossdeckErrorType(public val wireValue: String) {
    /** SDK or server rejected the request as malformed. Permanent. */
    INVALID_REQUEST("invalid_request_error"),

    /** Caller not authenticated. */
    AUTHENTICATION("authentication_error"),

    /** Authenticated but not authorised. */
    PERMISSION("permission_error"),

    /** Rate limit hit. Honour `Retry-After`. */
    RATE_LIMIT("rate_limit_error"),

    /**
     * Server-side issue (5xx). Aligned with the backend's
     * `ApiErrorType` wire vocabulary (`backend/src/api/v1-errors.ts`)
     * as of v1.4.0. Pre-1.4.0 the SDK declared `API_ERROR` /
     * `UNKNOWN` for these cases but the backend NEVER emitted
     * those tokens — native pattern-matching on `API_ERROR`
     * matched only the SDK-synthesised fallback, never a real
     * backend envelope. Use `INTERNAL_ERROR` to match backend
     * 5xx responses.
     */
    INTERNAL_ERROR("internal_error"),

    /**
     * SDK initialisation rejected (bad publishable key, missing
     * appId, environment mismatch). Mirrors the TS SDKs'
     * `configuration_error` type. Backend never emits this — it
     * is a client-side fail-fast surface.
     */
    CONFIGURATION_ERROR("configuration_error"),

    /** Network refused. Client-side only — backend never emits this. */
    NETWORK("network_error"),

    /**
     * **Deprecated as of v1.4.0** — backend never emits
     * `"api_error"` on the wire. Use `INTERNAL_ERROR` for 5xx
     * responses. Retained for source-compat; new code MUST NOT
     * pattern-match on this case.
     */
    @Deprecated(
        message = "Backend never emits 'api_error'; use INTERNAL_ERROR for 5xx responses (v1.4.0 wire vocabulary alignment).",
        replaceWith = ReplaceWith("INTERNAL_ERROR"),
        level = DeprecationLevel.WARNING,
    )
    API_ERROR("api_error"),

    /**
     * **Deprecated as of v1.4.0** — backend never emits
     * `"unknown_error"` on the wire. Catch-all for unmodelled
     * failure modes; future versions will remove this case and
     * require a specific type at every call site.
     */
    @Deprecated(
        message = "Backend never emits 'unknown_error'; specific error types preferred (v1.4.0 wire vocabulary alignment).",
        level = DeprecationLevel.WARNING,
    )
    UNKNOWN("unknown_error"),
    ;

    public companion object {
        public fun fromWire(value: String?): CrossdeckErrorType =
            values().firstOrNull { it.wireValue == value } ?: INTERNAL_ERROR
    }
}

/**
 * Bank-grade error envelope. Subclass of [Exception] so it flows
 * through standard Kotlin try/catch. All fields are read-only —
 * instances are safe to share across threads.
 */
public class CrossdeckError(
    public val type: CrossdeckErrorType,
    /** Stable machine-readable token (e.g. `missing_event_name`). */
    public val code: String,
    /** Human-readable description — DO NOT pattern-match on this. */
    message: String,
    /** Server-side request id for support traces. */
    public val requestId: String? = null,
    /** HTTP status code when the error came from a network response. */
    public val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String {
        val parts = mutableListOf("Crossdeck[${type.wireValue}:$code] ${message ?: ""}")
        requestId?.let { parts.add("(request_id: $it)") }
        statusCode?.let { parts.add("[HTTP $it]") }
        return parts.joinToString(" ")
    }
}

/**
 * Build a [CrossdeckError] from an HTTP response. Tries to decode
 * the structured envelope from the body first, falls back to
 * status-code-derived defaults. Never throws — malformed response
 * JSON should not itself crash the SDK.
 */
public fun crossdeckErrorFromResponse(
    statusCode: Int,
    requestIdHeader: String?,
    body: String?,
): CrossdeckError {
    var parsedType: CrossdeckErrorType? = null
    var parsedCode: String? = null
    var parsedMsg: String? = null
    var parsedReqId: String? = null

    if (!body.isNullOrEmpty()) {
        try {
            val root = JSONObject(body)
            val err = root.optJSONObject("error")
            if (err != null) {
                parsedType = CrossdeckErrorType.fromWire(err.optString("type", null))
                parsedCode = err.optString("code", null)?.takeIf { it.isNotEmpty() }
                parsedMsg = err.optString("message", null)?.takeIf { it.isNotEmpty() }
                parsedReqId = err.optString("request_id", null)?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            // Not JSON (e.g. an HTML 502 from upstream proxy) —
            // fall through to status-derived defaults.
        }
    }

    return CrossdeckError(
        type = parsedType ?: typeForStatus(statusCode),
        code = parsedCode ?: codeForStatus(statusCode),
        message = parsedMsg ?: defaultMessageForStatus(statusCode),
        requestId = parsedReqId ?: requestIdHeader,
        statusCode = statusCode,
    )
}

@Suppress("DEPRECATION")
private fun typeForStatus(status: Int): CrossdeckErrorType = when (status) {
    400, 422 -> CrossdeckErrorType.INVALID_REQUEST
    401 -> CrossdeckErrorType.AUTHENTICATION
    403 -> CrossdeckErrorType.PERMISSION
    429 -> CrossdeckErrorType.RATE_LIMIT
    // v1.4.0 — align fallback type with backend wire vocabulary.
    // Backend's ApiErrorType uses "internal_error" for 5xx, never
    // "api_error" (which used to be the SDK-only synthesised
    // fallback that nothing on the wire matched).
    in 500..599 -> CrossdeckErrorType.INTERNAL_ERROR
    else -> CrossdeckErrorType.INTERNAL_ERROR
}

private fun codeForStatus(status: Int): String = when (status) {
    400 -> "bad_request"
    401 -> "unauthorized"
    403 -> "forbidden"
    404 -> "not_found"
    422 -> "unprocessable_entity"
    429 -> "rate_limited"
    in 500..599 -> "server_error"
    else -> "http_$status"
}

private fun defaultMessageForStatus(status: Int): String = when (status) {
    400 -> "Request was malformed."
    401 -> "Authentication failed — check your publishable key + environment."
    403 -> "Authenticated, but not permitted to perform this action."
    404 -> "Endpoint not found."
    422 -> "Request payload failed validation."
    429 -> "Rate limit exceeded — back off and retry."
    in 500..599 -> "Server error — Crossdeck will retry."
    else -> "Request failed with HTTP $status."
}

/**
 * Parse a `Retry-After` header. Spec allows delta-seconds or HTTP-date.
 * Returns millis to wait, or null if absent / unparseable.
 */
public fun parseRetryAfterHeader(value: String?): Long? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null

    raw.toLongOrNull()?.let { return if (it < 0) null else it * 1_000L }

    return try {
        // Construct a fresh SimpleDateFormat per call — the JDK
        // class is NOT thread-safe and caching it (e.g. as a
        // module-level val) would be a foot-gun under concurrent
        // retry-after parsing. Allocating a few times per HTTP
        // response is negligible vs the IO that just completed.
        // Locale.US + GMT pinned so a device whose locale formats
        // dates differently still parses the spec'd HTTP-date
        // shape.
        val sdf = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z",
            java.util.Locale.US,
        )
        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val date = sdf.parse(raw) ?: return null
        (date.time - System.currentTimeMillis()).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}
