package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade entitlement cache contract (the per-user isolation
 * three-layer rule is covered by EntitlementCacheIsolationTest).
 *
 * This file covers the non-isolation guarantees:
 *
 *   - empty cache returns null / false.
 *   - write/read round-trips with user scoping.
 *   - persistence: a second cache bound to the same storage sees
 *     the prior writes.
 *   - per-entitlement validUntil expiry — even a "fresh" snapshot
 *     filters out expired entries.
 *   - markRefreshFailed() preserves the cache (outage = preserve;
 *     last-known-good wins until the next successful refresh).
 *   - subscribers do NOT fire on subscribe.
 */
class EntitlementCacheTest {

    private class FakeStorage : KeyValueStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun setString(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    private fun entitlement(
        key: String,
        active: Boolean = true,
        validUntil: Long? = null,
    ): PublicEntitlement =
        PublicEntitlement(
            key = key,
            isActive = active,
            validUntil = validUntil,
            source = PublicEntitlement.EntitlementSource(
                rail = AuditRail.STRIPE,
                productId = "p_$key",
                subscriptionId = "sub_$key",
            ),
            updatedAt = System.currentTimeMillis(),
        )

    @Test
    fun `empty cache returns null and isEntitled false`() {
        val cache = EntitlementCache(FakeStorage())
        assertNull(cache.entitlementsFor("user_a"))
        assertFalse(cache.isEntitled("pro", "user_a"))
    }

    @Test
    fun `write then read round-trips for the same user`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        cache.write(EntitlementSnapshot("user_a", listOf(entitlement("pro"))))
        assertTrue(cache.isEntitled("pro", "user_a"))
        assertEquals(1, cache.entitlementsFor("user_a")?.size)
    }

    @Test
    fun `a read scoped to a different user returns null`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        cache.write(EntitlementSnapshot("user_a", listOf(entitlement("pro"))))
        // Same cache, different user id on the read path.
        assertNull(cache.entitlementsFor("user_b"))
    }

    @Test
    fun `cache survives a fresh instance bound to the same storage`() {
        val storage = FakeStorage()
        val first = EntitlementCache(storage)
        first.setUserKey("user_a")
        first.write(EntitlementSnapshot("user_a", listOf(entitlement("pro"))))

        val reopened = EntitlementCache(storage)
        reopened.setUserKey("user_a")
        assertTrue(reopened.isEntitled("pro", "user_a"))
    }

    @Test
    fun `expired entitlement is filtered out even when snapshot is fresh`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        val expired = entitlement("pro", validUntil = System.currentTimeMillis() - 1_000L)
        cache.write(EntitlementSnapshot("user_a", listOf(expired)))
        assertFalse(cache.isEntitled("pro", "user_a"))
        // Snapshot is still cached — the filter is on read, not write.
        assertNotNull(cache.freshness())
    }

    @Test
    fun `inactive entitlement is filtered out`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        cache.write(EntitlementSnapshot("user_a", listOf(entitlement("pro", active = false))))
        assertFalse(cache.isEntitled("pro", "user_a"))
    }

    @Test
    fun `markRefreshFailed preserves the live entitlement set`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        cache.write(EntitlementSnapshot("user_a", listOf(entitlement("pro"))))
        // Simulate the network refresh exploding repeatedly.
        cache.markRefreshFailed()
        cache.markRefreshFailed()
        // Customer is STILL entitled — last-known-good wins.
        assertTrue("outage must not drop paying customer to free", cache.isEntitled("pro", "user_a"))
        val (_, _, lastFailedAt) = cache.freshness()!!
        assertNotNull(lastFailedAt)
    }

    @Test
    fun `subscribers do not fire on subscribe`() {
        val cache = EntitlementCache(FakeStorage())
        var calls = 0
        cache.subscribe { calls += 1 }
        // No write yet — must not have been notified.
        assertEquals(0, calls)
    }

    @Test
    fun `subscribers fire on write and on clear`() {
        val cache = EntitlementCache(FakeStorage())
        cache.setUserKey("user_a")
        var lastSeen: EntitlementSnapshot? = null
        var calls = 0
        cache.subscribe { snap -> calls += 1; lastSeen = snap }

        cache.write(EntitlementSnapshot("user_a", listOf(entitlement("pro"))))
        assertEquals(1, calls)
        assertEquals(1, lastSeen?.entitlements?.size)

        cache.clear()
        assertEquals(2, calls)
        assertNull(lastSeen)
    }
}
