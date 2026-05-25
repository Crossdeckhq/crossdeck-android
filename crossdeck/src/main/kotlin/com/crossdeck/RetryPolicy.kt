// Exponential backoff with full jitter, plus Retry-After honouring.
//
// Mathematical contract identical to web/node/swift/RN SDKs:
//   1) expected delay never exceeds maxMs (full-jitter guarantee)
//   2) Retry-After overrides local maxMs (server is authoritative)
//   3) Retry-After clamped at 24h (defence against malformed servers)

package com.crossdeck

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** 24h ceiling for any honoured `Retry-After`. */
public const val RETRY_AFTER_CEILING_MS: Long = 24L * 60L * 60L * 1_000L

public data class RetryPolicy(
    val baseMs: Long = 1_000L,
    val maxMs: Long = 30_000L,
    val factor: Double = 2.0,
    val maxAttempts: Int = 5,
) {
    /**
     * Returns delay in ms for a given attempt (0-indexed), or null
     * when the policy is exhausted.
     *
     * @param random Random source — injected for deterministic tests.
     */
    public fun nextDelayMs(
        attempt: Int,
        retryAfterMs: Long? = null,
        random: () -> Double = { Random.nextDouble() },
    ): Long? {
        if (attempt >= maxAttempts) return null

        if (retryAfterMs != null && retryAfterMs > 0) {
            // Server-authoritative — honour up to 24h ceiling.
            // Deliberately allowed to exceed local maxMs.
            return min(retryAfterMs, RETRY_AFTER_CEILING_MS)
        }

        // Cap exponent to prevent Double overflow on degenerate
        // attempt counts.
        val safeExponent = min(attempt.toDouble(), 30.0)
        val exponential = baseMs.toDouble() * factor.pow(safeExponent)
        val capped = min(exponential, maxMs.toDouble())
        val jittered = random() * capped

        return jittered.toLong().coerceAtLeast(0L)
    }
}
