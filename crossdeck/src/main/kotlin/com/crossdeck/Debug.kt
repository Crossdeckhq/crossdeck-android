// Structured debug-signal vocabulary.
//
// **CANONICAL CONTRACT — matches Web/Node/RN/Swift exactly.** The
// platform-wide signal vocabulary is defined per NorthStar §16:
// the dashboard's onboarding checklist keys off these specific
// signal names so it can show "we saw your first event" without
// parsing free-form output. Renaming a signal is a BREAKING
// dashboard change.

package com.crossdeck

import android.util.Log

public enum class DebugSignal(public val wireValue: String) {
    /** SDK successfully started + ready to accept track/identify. */
    SDK_CONFIGURED("sdk.configured"),

    /** First event of this process landed at the ingest endpoint. ONE-SHOT. */
    SDK_FIRST_EVENT_SENT("sdk.first_event_sent"),

    /** `publicKey` doesn't start with `cd_pub_`. Always loud. */
    SDK_INVALID_KEY("sdk.invalid_key"),

    /** `track()` fired without a known identity. */
    SDK_NO_IDENTITY("sdk.no_identity"),

    /** `isEntitled(...)` answered from the local cache. */
    SDK_ENTITLEMENT_CACHE_USED("sdk.entitlement_cache_used"),

    /** Purchase receipt successfully sent. */
    SDK_PURCHASE_EVIDENCE_SENT("sdk.purchase_evidence_sent"),

    /** Configured `environment` doesn't match the `publicKey` prefix. */
    SDK_ENVIRONMENT_MISMATCH("sdk.environment_mismatch"),

    /** Property key matched a PII/secret name pattern. */
    SDK_SENSITIVE_PROPERTY_WARNING("sdk.sensitive_property_warning"),

    /** A property value was coerced during sanitisation. */
    SDK_PROPERTY_COERCED("sdk.property_coerced"),

    /** Queue state persisted to storage. */
    SDK_QUEUE_PERSISTED("sdk.queue_persisted"),

    /** Queue state rehydrated from storage on start. */
    SDK_QUEUE_RESTORED("sdk.queue_restored"),

    /** Flush hit a retryable failure; retry scheduled. */
    SDK_FLUSH_RETRY_SCHEDULED("sdk.flush_retry_scheduled"),

    /** Queue dropped a batch — permanent 4xx or retry-exhausted. */
    SDK_FLUSH_PERMANENT_FAILURE("sdk.flush_permanent_failure"),

    /** Consent state changed via `setConsent(...)`. */
    SDK_CONSENT_CHANGED("sdk.consent_changed"),

    /** `track()` / `$error` dropped because consent denied for that channel. */
    SDK_CONSENT_DENIED("sdk.consent_denied"),

    /** Do-Not-Track applied (web parity; N/A on Android but defined for cross-SDK consistency). */
    SDK_CONSENT_DNT_APPLIED("sdk.consent_dnt_applied"),

    /** PII scrubber redacted a value on the wire. */
    SDK_PII_SCRUBBED("sdk.pii_scrubbed"),
}

/**
 * Closure invoked for each debug signal. Sendable so it can be
 * invoked from any coroutine context.
 */
public typealias DebugLogger = (signal: DebugSignal, payload: Map<String, String>) -> Unit

/** No-op logger. Default; zero alloc on the hot path. */
public val NoopDebugLogger: DebugLogger = { _, _ -> }

/**
 * Default logger backed by `android.util.Log` at INFO level under
 * the "Crossdeck" tag. Use during development; production builds
 * should leave the default no-op in place to avoid log spam.
 */
public val DefaultDebugLogger: DebugLogger = { signal, payload ->
    val rendered = if (payload.isEmpty()) {
        signal.wireValue
    } else {
        // Sorted for stable diffing across builds.
        val kv = payload.entries
            .sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${it.value}" }
        "${signal.wireValue} $kv"
    }
    Log.i("Crossdeck", rendered)
}

// MARK: - Sensitive-property name detection

/**
 * Property-name patterns that almost always indicate PII or
 * secret data. Per NorthStar §15 — warn (not reject) because a
 * property like `tokens_remaining` is a legitimate name. Patterns
 * mirror the RN + Swift SDKs exactly.
 */
private val SENSITIVE_KEY_PATTERNS: List<Regex> = listOf(
    Regex("^email$", RegexOption.IGNORE_CASE),
    Regex("^password$", RegexOption.IGNORE_CASE),
    Regex("^token$", RegexOption.IGNORE_CASE),
    Regex("^secret$", RegexOption.IGNORE_CASE),
    Regex("^card$", RegexOption.IGNORE_CASE),
    Regex("^phone$", RegexOption.IGNORE_CASE),
    Regex("password", RegexOption.IGNORE_CASE),
    Regex("credit_?card", RegexOption.IGNORE_CASE),
)

public fun findSensitivePropertyKeys(properties: Map<String, Any?>?): List<String> {
    if (properties.isNullOrEmpty()) return emptyList()
    val hits = mutableListOf<String>()
    for (key in properties.keys) {
        if (SENSITIVE_KEY_PATTERNS.any { it.containsMatchIn(key) }) {
            hits.add(key)
        }
    }
    return hits
}
