package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ring-buffer semantics for the breadcrumb trail attached to every
 * captured error.
 *
 *   - bounded: capacity caps memory growth on a long-running session.
 *   - drop-oldest: newest context wins when full.
 *   - snapshot returns a defensive copy: mutations after read don't
 *     leak into the caller's view of "what we knew when the error fired".
 */
class BreadcrumbsTest {

    private fun crumb(message: String): Breadcrumb =
        Breadcrumb(category = BreadcrumbCategory.CUSTOM, message = message)

    @Test
    fun `ring drops oldest at capacity`() {
        val ring = Breadcrumbs(capacity = 3)
        ring.add(crumb("a"))
        ring.add(crumb("b"))
        ring.add(crumb("c"))
        ring.add(crumb("d")) // forces drop of "a"
        ring.add(crumb("e")) // forces drop of "b"

        val snapshot = ring.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("c", "d", "e"), snapshot.map { it.message })
    }

    @Test
    fun `clear empties the ring`() {
        val ring = Breadcrumbs(capacity = 5)
        ring.add(crumb("a"))
        ring.add(crumb("b"))
        ring.clear()
        assertEquals(0, ring.snapshot().size)
    }

    @Test
    fun `snapshot returns a defensive copy, not a live reference`() {
        val ring = Breadcrumbs(capacity = 5)
        ring.add(crumb("a"))
        val first = ring.snapshot()
        ring.add(crumb("b"))
        val second = ring.snapshot()

        // Mutating after the first read must not retro-grow that snapshot.
        assertEquals(1, first.size)
        assertEquals(2, second.size)
        assertNotSame(first, second)
    }

    @Test
    fun `add rejects non-positive capacity`() {
        try {
            Breadcrumbs(capacity = 0)
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }
}
