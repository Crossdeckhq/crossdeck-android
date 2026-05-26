package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade PII + consent contracts.
 *
 * Scrubbing:
 *   - emails → `<email>` token, cards → `<card>` token (vocab is
 *     platform-wide; cross-SDK parity with web/node/RN/swift).
 *   - recursive over nested maps + lists.
 *   - depth-guarded so a pathological cyclic graph cannot blow
 *     the stack.
 *
 * Consent state:
 *   - default GRANTS analytics + errors (matches platform contract).
 *   - update is observable through `state`.
 */
class ConsentTest {

    @Test
    fun `scrubPii replaces email with token`() {
        assertEquals("contact <email> for details", scrubPii("contact wes@example.com for details"))
    }

    @Test
    fun `scrubPii replaces card number with token`() {
        assertEquals("paid via <card> last night", scrubPii("paid via 4242 4242 4242 4242 last night"))
    }

    @Test
    fun `scrubPiiDeep walks nested maps`() {
        val input = mapOf(
            "user" to mapOf(
                "contact" to mapOf("email" to "user@example.com"),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val out = scrubPiiDeep(input) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val contact = (out["user"] as Map<String, Any?>)["contact"] as Map<String, Any?>
        assertEquals("<email>", contact["email"])
    }

    @Test
    fun `scrubPiiDeep walks lists`() {
        val input = listOf("ok", "ping wes@example.com")
        @Suppress("UNCHECKED_CAST")
        val out = scrubPiiDeep(input) as List<Any?>
        assertEquals("ok", out[0])
        assertEquals("ping <email>", out[1])
    }

    @Test
    fun `scrubPiiDeep respects maxDepth and emits marker`() {
        // Build a deep map structure exceeding the depth cap.
        var nested: Any? = "wes@example.com"
        repeat(10) { nested = mapOf("k" to nested) }
        val out = scrubPiiDeep(nested, maxDepth = 3)
        // Drilling past depth 3 should hit the marker; we just need
        // to confirm the marker shows up SOMEWHERE in the tree.
        val flat = out.toString()
        assertTrue("expected <crossdeck:scrub:max-depth> in: $flat", flat.contains("<crossdeck:scrub:max-depth>"))
    }

    @Test
    fun `scrubPiiDeep leaves non-string primitives untouched`() {
        val input = mapOf("n" to 42, "ok" to true, "z" to null)
        @Suppress("UNCHECKED_CAST")
        val out = scrubPiiDeep(input) as Map<String, Any?>
        assertEquals(42, out["n"])
        assertEquals(true, out["ok"])
        assertNull(out["z"])
    }

    @Test
    fun `ConsentManager defaults to grant for analytics and errors`() {
        val mgr = ConsentManager()
        assertTrue(mgr.state.analytics)
        assertTrue(mgr.state.errors)
    }

    @Test
    fun `ConsentManager update applies new state atomically`() {
        val mgr = ConsentManager()
        mgr.update(ConsentState(analytics = false, errors = true))
        assertEquals(false, mgr.state.analytics)
        assertEquals(true, mgr.state.errors)
    }

    @Test
    fun `ConsentManager scrubPii toggle is observable`() {
        val mgr = ConsentManager()
        assertTrue(mgr.scrubPiiEnabled)
        mgr.setScrubPii(false)
        assertEquals(false, mgr.scrubPiiEnabled)
    }
}
