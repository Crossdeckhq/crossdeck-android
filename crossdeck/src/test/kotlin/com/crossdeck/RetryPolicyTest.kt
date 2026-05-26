package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade retry policy contract (parity with web/node/RN/swift):
 *
 *   1. Exhaustion past maxAttempts returns null (caller must drop).
 *   2. Full-jitter backoff: expected delay never exceeds maxMs.
 *   3. Retry-After is server-authoritative — allowed to exceed
 *      maxMs (a 429 with Retry-After: 60 must yield ~60s even if
 *      maxMs is 5s).
 *   4. Pathologically large Retry-After is clamped at 24h
 *      (RETRY_AFTER_CEILING_MS) — defence against malformed servers.
 */
class RetryPolicyTest {

    @Test
    fun `returns null past maxAttempts`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertNull(p.nextDelayMs(attempt = 3))
        assertNull(p.nextDelayMs(attempt = 99))
    }

    @Test
    fun `grows exponentially and caps at maxMs`() {
        val p = RetryPolicy(baseMs = 100, maxMs = 5_000, factor = 2.0, maxAttempts = 20)
        // Force jitter to 1.0 so we see the upper bound the policy permits.
        for (attempt in 0..15) {
            val d = p.nextDelayMs(attempt = attempt, random = { 1.0 })
            assertNotNull("attempt $attempt should yield a delay", d)
            assertTrue("attempt $attempt: $d exceeds maxMs", d!! <= 5_000)
        }
        // After the cap kicks in, the value should equal maxMs at jitter=1.
        assertEquals(5_000L, p.nextDelayMs(attempt = 15, random = { 1.0 }))
    }

    @Test
    fun `honours Retry-After above local maxMs`() {
        val p = RetryPolicy(maxMs = 5_000)
        val d = p.nextDelayMs(attempt = 0, retryAfterMs = 60_000)
        assertEquals(60_000L, d)
    }

    @Test
    fun `clamps a pathological Retry-After to 24h ceiling`() {
        val p = RetryPolicy()
        val d = p.nextDelayMs(attempt = 0, retryAfterMs = Long.MAX_VALUE)
        assertEquals(RETRY_AFTER_CEILING_MS, d)
    }

    @Test
    fun `ignores non-positive Retry-After and falls through to exponential`() {
        val p = RetryPolicy(baseMs = 1_000, maxMs = 1_000)
        val d = p.nextDelayMs(attempt = 0, retryAfterMs = 0, random = { 1.0 })
        assertEquals(1_000L, d)
    }

    @Test
    fun `full-jitter: zero jitter draws produce a zero floor`() {
        val p = RetryPolicy()
        val d = p.nextDelayMs(attempt = 0, random = { 0.0 })
        assertEquals(0L, d)
    }
}
