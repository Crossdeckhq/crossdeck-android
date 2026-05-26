package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade super-properties contract — cross-SDK parity with
 * web/node/RN/swift:
 *
 *   - register sets / overwrites a value.
 *   - registerOnce sets only if absent (no overwrite).
 *   - values persist across instances bound to the same storage.
 *   - unregister removes a single key.
 *   - clear wipes every key + the persisted blob.
 *   - empty keys are silently ignored (no throw at the call site).
 */
class SuperPropertiesTest {

    private class FakeStorage : KeyValueStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun setString(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    @Test
    fun `register sets a value`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("plan", "pro")
        assertEquals("pro", sp.snapshot()["plan"])
    }

    @Test
    fun `register overwrites an existing value`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("plan", "free")
        sp.register("plan", "pro")
        assertEquals("pro", sp.snapshot()["plan"])
    }

    @Test
    fun `registerOnce does not overwrite an existing value`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("plan", "pro")
        sp.registerOnce("plan", "free")
        assertEquals("pro", sp.snapshot()["plan"])
    }

    @Test
    fun `registerOnce sets when key is absent`() {
        val sp = SuperProperties(FakeStorage())
        sp.registerOnce("plan", "pro")
        assertEquals("pro", sp.snapshot()["plan"])
    }

    @Test
    fun `values persist across instances bound to the same storage`() {
        val storage = FakeStorage()
        SuperProperties(storage).register("plan", "pro")
        val reopened = SuperProperties(storage)
        assertEquals("pro", reopened.snapshot()["plan"])
    }

    @Test
    fun `unregister removes one key, leaves the rest`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("plan", "pro")
        sp.register("locale", "en")
        sp.unregister("plan")
        val snap = sp.snapshot()
        assertNull(snap["plan"])
        assertEquals("en", snap["locale"])
    }

    @Test
    fun `clear wipes every key`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("plan", "pro")
        sp.register("locale", "en")
        sp.clear()
        assertTrue(sp.snapshot().isEmpty())
    }

    @Test
    fun `empty key is ignored on register and registerOnce`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("", "ignored")
        sp.registerOnce("", "also ignored")
        assertFalse(sp.snapshot().containsKey(""))
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val sp = SuperProperties(FakeStorage())
        sp.register("a", "1")
        val first = sp.snapshot()
        sp.register("b", "2")
        assertFalse("first snapshot should not retro-grow", first.containsKey("b"))
    }
}
