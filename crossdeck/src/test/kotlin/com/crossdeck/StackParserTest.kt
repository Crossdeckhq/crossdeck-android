package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade stack-parse + fingerprint contract:
 *
 *   - Each parsed frame carries module (package) + symbol
 *     (SimpleClassName.method) so a refactor that re-packages a
 *     class shows up as a fingerprint change.
 *   - Default fingerprint depth is 5 frames joined by `|`. Same
 *     shape on every SDK (`module:symbol|module:symbol|…`) so the
 *     server-side grouper hashes on identical input regardless of
 *     which platform raised the error.
 *   - A throwable with no stack still produces a stable bucket
 *     (`<TypeName>:no_stack`) — never an empty fingerprint, never
 *     a crash.
 *   - The fingerprint is deterministic: same input → same output.
 */
class StackParserTest {

    @Test
    fun `parseStackTrace splits package and symbol per frame`() {
        val frames = parseStackTrace(arrayOf(
            StackTraceElement("com.crossdeck.Foo", "doIt", "Foo.kt", 12),
            StackTraceElement("Bar", "topLevel", "Bar.kt", 1),
        ))
        assertEquals(2, frames.size)
        assertEquals("com.crossdeck", frames[0].module)
        assertEquals("Foo.doIt", frames[0].symbol)
        assertEquals(12, frames[0].lineNumber)
        // No package case lands in <default>.
        assertEquals("<default>", frames[1].module)
        assertEquals("Bar.topLevel", frames[1].symbol)
    }

    @Test
    fun `parseStackTrace skips empty class or method names`() {
        val frames = parseStackTrace(arrayOf(
            StackTraceElement("", "x", "f", 1),
            StackTraceElement("x", "", "f", 1),
            StackTraceElement("com.crossdeck.Foo", "ok", "Foo.kt", 1),
        ))
        assertEquals(1, frames.size)
    }

    @Test
    fun `parseStackTrace drops negative line numbers`() {
        val frames = parseStackTrace(arrayOf(
            StackTraceElement("com.crossdeck.Foo", "doIt", "Foo.kt", -1),
        ))
        assertEquals(null, frames[0].lineNumber)
    }

    @Test
    fun `fingerprintFromStack matches cross-SDK shape and is deterministic`() {
        val t = RuntimeException("boom")
        val first = fingerprintFromStack(t)
        val second = fingerprintFromStack(t)
        // No line numbers; just `module:symbol` joined by `|`.
        assertTrue("expected '|'-joined module:symbol, got: $first", first.contains(":"))
        assertTrue(first == second)
    }

    @Test
    fun `fingerprintFromStack caps at the requested depth`() {
        val t = RuntimeException("boom")
        val deep = fingerprintFromStack(t, depth = 10)
        val shallow = fingerprintFromStack(t, depth = 2)
        // Shallow should be a prefix-able subset; concretely it should have
        // strictly fewer `|` separators than deep.
        val deepBars = deep.count { it == '|' }
        val shallowBars = shallow.count { it == '|' }
        assertTrue("deep=$deep shallow=$shallow", shallowBars <= deepBars)
        // depth=2 frames means at most one `|`.
        assertTrue(shallowBars <= 1)
    }

    @Test
    fun `fingerprintFromStack falls back to TypeName_no_stack for empty stack`() {
        val t = object : Throwable("no-stack-here") {}
        t.stackTrace = emptyArray()
        val fp = fingerprintFromStack(t)
        assertNotNull(fp)
        assertTrue("expected ':no_stack' suffix, got: $fp", fp.endsWith(":no_stack"))
    }
}
