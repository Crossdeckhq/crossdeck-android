package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade identity contract:
 *
 *   - anonymousId is minted on first launch, prefixed `anon_`,
 *     and persists across process restarts.
 *   - setDeveloperUserId is idempotent (same value → no-op,
 *     returns false) and normalises whitespace/empty → null.
 *   - setCrossdeckCustomerId follows the same idempotency contract.
 *   - reset() clears the developer + customer ids AND regenerates
 *     the anonymousId — a shared device cannot link the next
 *     anonymous session to the just-signed-out user.
 */
class IdentityTest {

    private class FakeStorage : KeyValueStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun setString(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    @Test
    fun `generates an anonymous id on first launch`() {
        val storage = FakeStorage()
        val identity = Identity(storage)
        val snap = identity.snapshot()
        assertTrue("anonymous id should be prefixed: ${snap.anonymousId}", snap.anonymousId.startsWith("anon_"))
        assertTrue(snap.anonymousId.length > "anon_".length + 8)
        assertNull(snap.developerUserId)
        assertNull(snap.crossdeckCustomerId)
    }

    @Test
    fun `persists the anonymous id across instances`() {
        val storage = FakeStorage()
        val first = Identity(storage).snapshot().anonymousId
        val second = Identity(storage).snapshot().anonymousId
        assertEquals(first, second)
    }

    @Test
    fun `setDeveloperUserId is idempotent for the same value`() {
        val identity = Identity(FakeStorage())
        assertTrue(identity.setDeveloperUserId("user_847"))
        assertFalse(identity.setDeveloperUserId("user_847"))
        assertEquals("user_847", identity.snapshot().developerUserId)
    }

    @Test
    fun `setDeveloperUserId normalises whitespace and nils empty`() {
        val identity = Identity(FakeStorage())
        identity.setDeveloperUserId("  user_847  ")
        assertEquals("user_847", identity.snapshot().developerUserId)
        identity.setDeveloperUserId("")
        assertNull(identity.snapshot().developerUserId)
        identity.setDeveloperUserId("   ")
        assertNull(identity.snapshot().developerUserId)
    }

    @Test
    fun `setCrossdeckCustomerId persists and is idempotent`() {
        val storage = FakeStorage()
        val identity = Identity(storage)
        assertTrue(identity.setCrossdeckCustomerId("cdcust_abc"))
        assertFalse(identity.setCrossdeckCustomerId("cdcust_abc"))
        assertEquals("cdcust_abc", identity.snapshot().crossdeckCustomerId)
        // Survives re-load from storage.
        val reloaded = Identity(storage)
        assertEquals("cdcust_abc", reloaded.snapshot().crossdeckCustomerId)
    }

    @Test
    fun `reset regenerates the anonymous id and clears customer state`() {
        val storage = FakeStorage()
        val identity = Identity(storage)
        identity.setDeveloperUserId("user_847")
        identity.setCrossdeckCustomerId("cdcust_abc")
        val before = identity.snapshot().anonymousId

        identity.reset()

        val after = identity.snapshot()
        assertNull(after.developerUserId)
        assertNull(after.crossdeckCustomerId)
        assertNotEquals(before, after.anonymousId)
        assertTrue(after.anonymousId.startsWith("anon_"))
    }
}
