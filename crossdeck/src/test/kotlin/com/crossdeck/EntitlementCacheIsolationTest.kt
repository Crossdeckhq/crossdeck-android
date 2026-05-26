package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.3-android contract tests — bank-grade per-user
 * entitlement cache isolation parity with Web/RN.
 *
 * The same contract registered in
 * `contracts/entitlements/per-user-cache-isolation.json`
 * — Android joined the applies_to list in v1.4.x after the
 * founder caught the missing entry in the dogfood pass.
 *
 * Three-layer invariants under test:
 *   (a) physical key separation per user
 *   (b) identify() unconditionally wipes in-memory + flips suffix
 *   (c) reset() / clearAll() wipes EVERY per-user slot via the
 *       persisted index
 */
class EntitlementCacheIsolationTest {

    private class InspectableMemoryStorage : KeyValueStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun setString(key: String, value: String) {
            data[key] = value
        }
        override fun remove(key: String) {
            data.remove(key)
        }
    }

    private fun entitlement(key: String, productId: String = "p_$key"): PublicEntitlement =
        PublicEntitlement(
            key = key,
            isActive = true,
            validUntil = null,
            source = PublicEntitlement.EntitlementSource(
                rail = AuditRail.APPLE,
                productId = productId,
                subscriptionId = "sub_$key",
            ),
            updatedAt = System.currentTimeMillis(),
        )

    // ---- Layer (a): physical key separation per user ----

    @Test
    fun `identified writes land under per-user sha256 key`() {
        val storage = InspectableMemoryStorage()
        val cache = EntitlementCache(storage)
        cache.setUserKey("alice")
        cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))

        val expectedKey = "crossdeck:entitlements:${IdempotencyKey.sha256Hex("alice")}"
        assertTrue("Expected key $expectedKey present", storage.data.containsKey(expectedKey))
    }

    @Test
    fun `two users use two different storage keys`() {
        val storage = InspectableMemoryStorage()
        val cache = EntitlementCache(storage)

        cache.setUserKey("alice")
        cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))
        cache.setUserKey("bob")
        cache.write(EntitlementSnapshot("bob", listOf(entitlement("trial"))))

        val aliceKey = "crossdeck:entitlements:${IdempotencyKey.sha256Hex("alice")}"
        val bobKey = "crossdeck:entitlements:${IdempotencyKey.sha256Hex("bob")}"
        assertTrue(storage.data.containsKey(aliceKey))
        assertTrue(storage.data.containsKey(bobKey))
        assertFalse("Per-user keys must differ", aliceKey == bobKey)
    }

    // ---- Layer (b): identify() unconditional in-memory wipe ----

    @Test
    fun `identify B makes A entitlements unreachable from in-memory`() {
        // The flagship contract: switching from user A to user B
        // must NOT let isEntitled() observe A's cache via the
        // sync read path, even if the in-memory wipe was somehow
        // skipped (per-user storage keys are physically separate).
        val storage = InspectableMemoryStorage()
        val cache = EntitlementCache(storage)

        cache.setUserKey("alice")
        cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))
        assertTrue(cache.isEntitled("pro", "alice"))

        cache.setUserKey("bob")

        // Bob's slot is empty — isEntitled("pro", "bob") false.
        assertFalse(cache.isEntitled("pro", "bob"))
        // The active in-memory snapshot belongs to bob's slot,
        // which is empty.
        assertNull(cache.entitlementsFor("bob"))
    }

    @Test
    fun `identify B then identify A rehydrates A's slot`() {
        val storage = InspectableMemoryStorage()
        val cache = EntitlementCache(storage)

        cache.setUserKey("alice")
        cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))
        cache.setUserKey("bob")
        cache.write(EntitlementSnapshot("bob", listOf(entitlement("trial"))))

        cache.setUserKey("alice")
        assertTrue("A's pro entitlement must rehydrate", cache.isEntitled("pro", "alice"))
        assertFalse(cache.isEntitled("trial", "alice"))
    }

    // ---- Layer (c): clearAll() wipes EVERY per-user slot ----

    @Test
    fun `clearAll removes every per-user storage key plus the index`() {
        val storage = InspectableMemoryStorage()
        val cache = EntitlementCache(storage)

        cache.setUserKey("alice"); cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))
        cache.setUserKey("bob"); cache.write(EntitlementSnapshot("bob", listOf(entitlement("trial"))))
        cache.setUserKey("charlie"); cache.write(EntitlementSnapshot("charlie", listOf(entitlement("enterprise"))))
        assertTrue(storage.data.size >= 4) // 3 user keys + 1 index

        cache.clearAll()

        val remaining = storage.data.keys.filter { it.startsWith("crossdeck:entitlements") }
        assertEquals(emptyList<String>(), remaining)
    }

    @Test
    fun `clearAll does NOT touch unrelated host-app storage keys`() {
        val storage = InspectableMemoryStorage()
        storage.setString("app:user_preferences", "{\"theme\":\"dark\"}")

        val cache = EntitlementCache(storage)
        cache.setUserKey("alice"); cache.write(EntitlementSnapshot("alice", listOf(entitlement("pro"))))

        cache.clearAll()

        assertEquals("{\"theme\":\"dark\"}", storage.getString("app:user_preferences"))
    }

    // ---- Defence-in-depth: physical isolation across cache instances ----

    @Test
    fun `a fresh cache bound to A's key CANNOT read B's blob`() {
        // Worst case: a refactor skips the in-memory wipe entirely.
        // Storage-level isolation MUST still hold — each user's blob
        // lives under their per-user key; no construction of a
        // different cache instance can cross-read.
        val storage = InspectableMemoryStorage()

        val aCache = EntitlementCache(storage)
        aCache.setUserKey("alice"); aCache.write(EntitlementSnapshot("alice", listOf(entitlement("pro_a"))))

        val bCache = EntitlementCache(storage)
        bCache.setUserKey("bob"); bCache.write(EntitlementSnapshot("bob", listOf(entitlement("pro_b"))))

        val rogueAlice = EntitlementCache(storage)
        rogueAlice.setUserKey("alice")
        assertTrue(rogueAlice.isEntitled("pro_a", "alice"))
        assertFalse(rogueAlice.isEntitled("pro_b", "alice"))

        val rogueBob = EntitlementCache(storage)
        rogueBob.setUserKey("bob")
        assertTrue(rogueBob.isEntitled("pro_b", "bob"))
        assertFalse(rogueBob.isEntitled("pro_a", "bob"))
    }
}
