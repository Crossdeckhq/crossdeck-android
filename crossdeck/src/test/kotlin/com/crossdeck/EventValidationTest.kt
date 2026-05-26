package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.Date
import java.util.UUID

/**
 * Bank-grade event-property validation contract (parity with
 * web/node/RN/swift):
 *
 *   sanitise + warn, NEVER throw. track() is fire-and-forget;
 *   making it throw on a single bad property breaks the consumer's
 *   call site.
 *
 * Coercion rules:
 *   - Date            → ISO 8601 string
 *   - URI             → string
 *   - UUID            → string
 *   - Double/Float NaN, +/-Inf → null + not_finite warning
 *   - String > MAX_STRING_LENGTH → truncated with ellipsis
 *   - Cyclic Map/List → "[circular]" + warning
 *   - Depth > MAX     → "[depth-exceeded]" + warning
 *   - Function/lambda → dropped + non_serialisable warning
 *   - Unknown class   → dropped + non_serialisable warning
 */
class EventValidationTest {

    @Test
    fun `accepts simple primitive properties unchanged`() {
        val r = validateEventProperties(mapOf("name" to "wes", "n" to 42, "ok" to true))
        assertEquals("wes", r.properties["name"])
        assertEquals(42, r.properties["n"])
        assertEquals(true, r.properties["ok"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `accepts nested map`() {
        val r = validateEventProperties(mapOf("u" to mapOf("plan" to "pro")))
        @Suppress("UNCHECKED_CAST")
        val u = r.properties["u"] as Map<String, Any?>
        assertEquals("pro", u["plan"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `coerces Double NaN to null and emits not_finite warning`() {
        val r = validateEventProperties(mapOf("d" to Double.NaN))
        assertNull(r.properties["d"])
        assertTrue(r.warnings.any { it.key == "d" && it.kind == ValidationWarningKind.NOT_FINITE })
    }

    @Test
    fun `coerces Double Infinity to null`() {
        val r = validateEventProperties(mapOf("d" to Double.POSITIVE_INFINITY))
        assertNull(r.properties["d"])
        assertTrue(r.warnings.any { it.kind == ValidationWarningKind.NOT_FINITE })
    }

    @Test
    fun `coerces Float NaN to null`() {
        val r = validateEventProperties(mapOf("f" to Float.NaN))
        assertNull(r.properties["f"])
        assertTrue(r.warnings.any { it.kind == ValidationWarningKind.NOT_FINITE })
    }

    @Test
    fun `drops a non-serialisable class instance and emits warning`() {
        class Pojo(val x: Int)
        val r = validateEventProperties(mapOf("p" to Pojo(1)))
        assertFalse(r.properties.containsKey("p"))
        assertTrue(r.warnings.any { it.key == "p" && it.kind == ValidationWarningKind.NON_SERIALISABLE })
    }

    @Test
    fun `drops a lambda and emits warning`() {
        val r = validateEventProperties(mapOf("fn" to { 1 }))
        assertFalse(r.properties.containsKey("fn"))
        assertTrue(r.warnings.any { it.key == "fn" && it.kind == ValidationWarningKind.NON_SERIALISABLE })
    }

    @Test
    fun `replaces cyclic Map with circular marker and emits warning`() {
        val a: MutableMap<String, Any?> = mutableMapOf()
        val b: MutableMap<String, Any?> = mutableMapOf("a" to a)
        a["b"] = b
        val r = validateEventProperties(mapOf("root" to a))
        // Drill into the structure looking for the marker.
        val flat = r.properties.toString()
        assertTrue("expected [circular] in: $flat", flat.contains("[circular]"))
        assertTrue(r.warnings.any { it.kind == ValidationWarningKind.CIRCULAR_REFERENCE })
    }

    @Test
    fun `replaces excessive depth with marker and emits warning`() {
        // Build a chain deeper than VALIDATION_MAX_DEPTH.
        var nested: Any = "leaf"
        repeat(VALIDATION_MAX_DEPTH + 5) { nested = mapOf("k" to nested) }
        val r = validateEventProperties(mapOf("root" to nested))
        val flat = r.properties.toString()
        assertTrue("expected [depth-exceeded] in: $flat", flat.contains("[depth-exceeded]"))
        assertTrue(r.warnings.any { it.kind == ValidationWarningKind.DEPTH_EXCEEDED })
    }

    @Test
    fun `truncates an oversize string and emits warning`() {
        val long = "x".repeat(MAX_STRING_LENGTH + 50)
        val r = validateEventProperties(mapOf("s" to long))
        val out = r.properties["s"] as String
        assertEquals(MAX_STRING_LENGTH, out.length)
        assertTrue(out.endsWith("…"))
        assertTrue(r.warnings.any { it.key == "s" && it.kind == ValidationWarningKind.TRUNCATED_STRING })
    }

    @Test
    fun `coerces Date to ISO 8601 UTC string`() {
        val r = validateEventProperties(mapOf("at" to Date(0)))
        assertEquals("1970-01-01T00:00:00.000Z", r.properties["at"])
    }

    @Test
    fun `coerces URI to string`() {
        val r = validateEventProperties(mapOf("u" to URI("https://cross-deck.com")))
        assertEquals("https://cross-deck.com", r.properties["u"])
    }

    @Test
    fun `coerces UUID to string`() {
        val uuid = UUID.fromString("a66b1640-efaf-bb4d-1261-6650033bf111")
        val r = validateEventProperties(mapOf("id" to uuid))
        assertEquals("a66b1640-efaf-bb4d-1261-6650033bf111", r.properties["id"])
    }

    @Test
    fun `never throws on a fully poisoned map`() {
        class Pojo
        val map: Map<String, Any?> = mapOf(
            "a" to Double.NaN,
            "b" to Pojo(),
            "c" to "x".repeat(MAX_STRING_LENGTH + 1),
            "d" to { 1 },
            "e" to "ok",
        )
        // No try/catch — the contract is "never throws".
        val r = validateEventProperties(map)
        assertEquals("ok", r.properties["e"])
        assertNotNull(r.warnings)
        assertTrue(r.warnings.isNotEmpty())
    }
}
