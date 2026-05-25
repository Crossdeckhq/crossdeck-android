// PII consent + scrubber.
//
// Two responsibilities:
//
//  1) Track per-channel consent (analytics + errors independently)
//     — default GRANT both, matches the Web/Node/RN/Swift platform
//     contract. Consumers opt-out via `Crossdeck.setConsent(...)`
//     for strict-consent flows (cookie banner / EU age gate).
//
//  2) Scrub PII out of event properties + error metadata before
//     the queue. Tokens are the platform-wide convention: <email>
//     and <card>, angle-bracketed. Scrubbing is applied recursively
//     to nested maps + lists so a payload like { user: { contact:
//     { email: "..." } } } is redacted at every depth.

package com.crossdeck

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

public data class ConsentState(
    val analytics: Boolean = true,
    val errors: Boolean = true,
)

/**
 * Thread-safe consent + scrub-toggle holder.
 *
 * Default state — `ConsentState(analytics: true, errors: true)` —
 * matches the platform contract. Consumers wire `setConsent(...)`
 * for an opt-out path. Default-DENY would silently drop every
 * event from a developer following the docs verbatim; that's the
 * wrong failure mode for a telemetry SDK.
 */
public class ConsentManager(
    initial: ConsentState = ConsentState(),
    initialScrubPii: Boolean = true,
) {
    private val lock = ReentrantReadWriteLock()
    private var _state: ConsentState = initial
    private var _scrubPii: Boolean = initialScrubPii

    public val state: ConsentState
        get() = lock.read { _state }

    public val scrubPiiEnabled: Boolean
        get() = lock.read { _scrubPii }

    public fun update(next: ConsentState) {
        lock.write { _state = next }
    }

    public fun setScrubPii(enabled: Boolean) {
        lock.write { _scrubPii = enabled }
    }
}

// MARK: - PII scrubbing

private val EMAIL_REGEX = Regex(
    """[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""",
)

private val CARD_REGEX = Regex("""\b(?:\d[ \-]*?){13,19}\b""")

/**
 * Scrub PII from a single string. Tokens (`<email>` / `<card>`)
 * match the platform-wide vocabulary — DO NOT alter without
 * simultaneously updating backend / web / node / RN / swift.
 */
public fun scrubPii(input: String): String {
    var s = input
    s = EMAIL_REGEX.replace(s, "<email>")
    s = CARD_REGEX.replace(s, "<card>")
    return s
}

/**
 * Recursively scrub PII from an arbitrary value tree. Handles
 * String, Map, List; leaves other primitive types untouched.
 * Depth-guarded so a pathological cyclic graph cannot blow the
 * stack (Kotlin's immutable Map / List are value-shaped and
 * can't cycle, but MutableMap / MutableList can).
 */
public fun scrubPiiDeep(value: Any?, maxDepth: Int = 64): Any? =
    scrubRecursive(value, 0, maxDepth)

private fun scrubRecursive(value: Any?, depth: Int, maxDepth: Int): Any? {
    if (depth > maxDepth) {
        return "<crossdeck:scrub:max-depth>"
    }
    return when (value) {
        null -> null
        is String -> scrubPii(value)
        is List<*> -> value.map { scrubRecursive(it, depth + 1, maxDepth) }
        is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>(value.size)
            for ((k, v) in value) {
                val keyName = (k as? String) ?: k.toString()
                out[keyName] = scrubRecursive(v, depth + 1, maxDepth)
            }
            out
        }
        else -> value
    }
}
