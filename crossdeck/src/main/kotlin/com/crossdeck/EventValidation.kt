// Wire-level event-property sanitisation + warning model.
//
// **Behaviour contract (matches Web/Node/RN/Swift exactly):**
//
//   sanitise + warn, NEVER throw.
//
// `track()` is fire-and-forget — making it throw on a single
// non-encodable property breaks the consumer's call site. Instead:
// the validator returns a cleaned copy with non-encodable / unsafe
// values coerced or dropped, plus a list of warnings the consumer
// can route to a debug logger.
//
// Coercion rules:
//   * `Date`               → ISO 8601 string
//   * `URI` / `URL`        → string
//   * `UUID`               → string
//   * `Double.NaN / Inf`   → null + `not_finite` warning
//   * `Float.NaN / Inf`    → null + `not_finite` warning
//   * `String` > maxStr    → truncated with ellipsis, warning
//   * Cyclic Map / List    → `"[circular]"`, warning
//   * Depth > maxDepth     → `"[depth-exceeded]"`, warning
//   * Function / lambda    → dropped, `non_serialisable` warning

package com.crossdeck

import java.net.URI
import java.util.Date
import java.util.UUID

/** Max string length on any single property value. */
public const val MAX_STRING_LENGTH: Int = 1024

/** Max nesting depth before the validator stops recursing. */
public const val VALIDATION_MAX_DEPTH: Int = 32

public enum class ValidationWarningKind(public val wireValue: String) {
    DEPTH_EXCEEDED("depth_exceeded"),
    CIRCULAR_REFERENCE("circular_reference"),
    TRUNCATED_STRING("truncated_string"),
    NON_SERIALISABLE("non_serialisable"),
    NOT_FINITE("not_finite"),
}

public data class ValidationWarning(
    val key: String,
    val kind: ValidationWarningKind,
)

public data class ValidationResult(
    val properties: Map<String, Any?>,
    val warnings: List<ValidationWarning>,
)

/**
 * Sanitise an event property map. Returns the cleaned bag plus a
 * list of warnings. NEVER throws — the bank-grade contract is
 * that `track()` always proceeds, even when properties had to be
 * coerced.
 */
public fun validateEventProperties(properties: Map<String, Any?>): ValidationResult {
    val warnings = mutableListOf<ValidationWarning>()
    val ancestors = mutableSetOf<Int>()
    val cleaned = sanitiseValue(properties, "<root>", 0, ancestors, warnings)
    @Suppress("UNCHECKED_CAST")
    val bag = (cleaned as? Map<String, Any?>) ?: emptyMap()
    return ValidationResult(bag, warnings)
}

private fun sanitiseValue(
    value: Any?,
    key: String,
    depth: Int,
    ancestors: MutableSet<Int>,
    warnings: MutableList<ValidationWarning>,
): Any? {
    if (depth > VALIDATION_MAX_DEPTH) {
        warnings.add(ValidationWarning(key, ValidationWarningKind.DEPTH_EXCEEDED))
        return "[depth-exceeded]"
    }
    if (value == null) return null

    when (value) {
        is String -> {
            if (value.length > MAX_STRING_LENGTH) {
                warnings.add(ValidationWarning(key, ValidationWarningKind.TRUNCATED_STRING))
                return value.substring(0, MAX_STRING_LENGTH - 1) + "…"
            }
            return value
        }
        is Boolean -> return value
        is Double -> {
            if (!value.isFinite()) {
                warnings.add(ValidationWarning(key, ValidationWarningKind.NOT_FINITE))
                return null
            }
            return value
        }
        is Float -> {
            if (!value.isFinite()) {
                warnings.add(ValidationWarning(key, ValidationWarningKind.NOT_FINITE))
                return null
            }
            return value
        }
        is Int, is Long, is Short, is Byte -> return value
        is Date -> {
            // ISO 8601 string with millisecond precision + UTC.
            return java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                java.util.Locale.US,
            ).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(value)
        }
        is URI -> return value.toString()
        is UUID -> return value.toString()
        is Map<*, *> -> {
            val id = System.identityHashCode(value)
            if (ancestors.contains(id)) {
                warnings.add(ValidationWarning(key, ValidationWarningKind.CIRCULAR_REFERENCE))
                return "[circular]"
            }
            ancestors.add(id)
            try {
                val out = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) {
                    val keyName = (k as? String) ?: k.toString()
                    val cleaned = sanitiseValue(v, keyName, depth + 1, ancestors, warnings)
                    if (cleaned != null) out[keyName] = cleaned
                }
                return out
            } finally {
                ancestors.remove(id)
            }
        }
        is List<*> -> {
            val id = System.identityHashCode(value)
            if (ancestors.contains(id)) {
                warnings.add(ValidationWarning(key, ValidationWarningKind.CIRCULAR_REFERENCE))
                return "[circular]"
            }
            ancestors.add(id)
            try {
                val out = mutableListOf<Any?>()
                for ((i, v) in value.withIndex()) {
                    val cleaned = sanitiseValue(v, "$key[$i]", depth + 1, ancestors, warnings)
                    out.add(cleaned)
                }
                return out
            } finally {
                ancestors.remove(id)
            }
        }
        is Function<*> -> {
            // Kotlin lambdas / Java functional interfaces — drop.
            warnings.add(ValidationWarning(key, ValidationWarningKind.NON_SERIALISABLE))
            return null
        }
        else -> {
            // Unknown class instance — not JSON-serialisable.
            warnings.add(ValidationWarning(key, ValidationWarningKind.NON_SERIALISABLE))
            return null
        }
    }
}
