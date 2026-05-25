// Entitlement cache.
//
// Stores the most recently fetched entitlement set for a customer
// scoped on `developerUserId` (until v1.1 wires the cdcust-keyed
// model the way Web persists it).
//
// **Bank-grade durability model (matches Web/Node/RN/Swift):**
//
//   * Per-entitlement `validUntil` honoured — a snapshot can be
//     fresh on the metadata level while a specific entitlement
//     has already expired. `isEntitled` checks both `isActive`
//     AND `validUntil > now`.
//
//   * Staleness model: `staleAfterMs` (default 60s) marks the
//     snapshot stale for UI refresh hints but `isEntitled` still
//     honours it.
//
//   * `markRefreshFailed()` records outage timestamps WITHOUT
//     invalidating the cache. A Crossdeck outage MUST NOT fail
//     a paying customer down to free — last-known-good wins.
//
//   * onChange subscribers do NOT fire on subscribe (matches the
//     Web/RN/Swift contract).

package com.crossdeck

import org.json.JSONArray
import org.json.JSONObject

/** Default staleness window — 60s matches platform contract. */
public const val DEFAULT_ENTITLEMENT_STALE_AFTER_MS: Long = 60_000L

public data class EntitlementSnapshot(
    val developerUserId: String,
    val entitlements: List<PublicEntitlement>,
    val lastUpdated: Long = System.currentTimeMillis(),
    val lastRefreshFailedAt: Long? = null,
)

public typealias EntitlementSubscriber = (EntitlementSnapshot?) -> Unit

public class EntitlementCache(
    private val storage: KeyValueStorage,
    private val staleAfterMs: Long = DEFAULT_ENTITLEMENT_STALE_AFTER_MS,
) {
    private val storageKey = "entitlements"
    private val lock = Any()
    private var current: EntitlementSnapshot? = null
    private val subscribers: MutableMap<String, EntitlementSubscriber> = LinkedHashMap()

    init {
        val blob = storage.getString(storageKey)
        if (!blob.isNullOrEmpty()) {
            decode(blob)?.let { current = it }
        }
    }

    /**
     * Synchronous accessor for the entitlement set scoped to a
     * user. Returns null if no snapshot is cached or if the
     * snapshot belongs to a different user. Filters expired
     * entries.
     */
    public fun entitlementsFor(developerUserId: String): List<PublicEntitlement>? {
        val snap = synchronized(lock) { current } ?: return null
        if (snap.developerUserId != developerUserId) return null
        val now = System.currentTimeMillis()
        return snap.entitlements.filter { ent ->
            ent.isActive && (ent.validUntil == null || ent.validUntil > now)
        }
    }

    /** Quick check. Same caveats as [entitlementsFor]. */
    public fun isEntitled(key: String, developerUserId: String): Boolean {
        val list = entitlementsFor(developerUserId) ?: return false
        return list.any { it.key == key }
    }

    /** Freshness diagnostic — exposed via `Crossdeck.diagnostics()`. */
    public fun freshness(): Triple<Long, Boolean, Long?>? {
        val snap = synchronized(lock) { current } ?: return null
        val now = System.currentTimeMillis()
        val isStale = (now - snap.lastUpdated) > staleAfterMs
        return Triple(snap.lastUpdated, isStale, snap.lastRefreshFailedAt)
    }

    public fun write(snapshot: EntitlementSnapshot) {
        val changed: Boolean
        synchronized(lock) {
            changed = (current != snapshot)
            current = snapshot
        }
        storage.setString(storageKey, encode(snapshot))
        if (changed) notifyAll(snapshot)
    }

    /**
     * Record a failed refresh attempt WITHOUT invalidating the
     * cache. Bank-grade rule: a Crossdeck outage MUST NOT fail a
     * paying customer down to free.
     */
    public fun markRefreshFailed() {
        synchronized(lock) {
            val existing = current ?: return@synchronized
            val updated = existing.copy(lastRefreshFailedAt = System.currentTimeMillis())
            current = updated
            storage.setString(storageKey, encode(updated))
        }
    }

    public fun clear() {
        synchronized(lock) {
            if (current == null) return@synchronized
            current = null
            storage.remove(storageKey)
        }
        notifyAll(null)
    }

    /**
     * Subscribe to changes. Returns an unsubscribe handle —
     * call to detach. Does NOT fire on subscribe (matches the
     * Web/RN/Swift contract).
     */
    public fun subscribe(handler: EntitlementSubscriber): () -> Unit {
        val token = java.util.UUID.randomUUID().toString()
        synchronized(lock) { subscribers[token] = handler }
        return { synchronized(lock) { subscribers.remove(token) } }
    }

    private fun notifyAll(snapshot: EntitlementSnapshot?) {
        val handlers = synchronized(lock) { subscribers.values.toList() }
        for (handler in handlers) {
            try {
                handler(snapshot)
            } catch (_: Throwable) {
                // Subscriber crash never propagates out of the cache.
            }
        }
    }

    // ----- JSON encoder / decoder -----

    private fun encode(snapshot: EntitlementSnapshot): String {
        val root = JSONObject()
        root.put("developerUserId", snapshot.developerUserId)
        root.put("lastUpdated", snapshot.lastUpdated)
        if (snapshot.lastRefreshFailedAt != null) {
            root.put("lastRefreshFailedAt", snapshot.lastRefreshFailedAt)
        }
        val arr = JSONArray()
        for (ent in snapshot.entitlements) arr.put(encodeEntitlement(ent))
        root.put("entitlements", arr)
        return root.toString()
    }

    private fun encodeEntitlement(ent: PublicEntitlement): JSONObject {
        val o = JSONObject()
        o.put("key", ent.key)
        o.put("isActive", ent.isActive)
        if (ent.validUntil != null) o.put("validUntil", ent.validUntil)
        o.put("updatedAt", ent.updatedAt)
        val src = JSONObject()
        src.put("rail", ent.source.rail.wireValue)
        src.put("productId", ent.source.productId)
        src.put("subscriptionId", ent.source.subscriptionId)
        o.put("source", src)
        return o
    }

    private fun decode(blob: String): EntitlementSnapshot? {
        return try {
            decodeInner(blob)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeInner(blob: String): EntitlementSnapshot? {
        val root = JSONObject(blob)
        val devUid = root.optString("developerUserId").takeIf { it.isNotEmpty() } ?: return null
        val lastUpdated = root.optLong("lastUpdated", System.currentTimeMillis())
        val lastFailedAt = if (root.has("lastRefreshFailedAt"))
            root.optLong("lastRefreshFailedAt") else null
        val arr = root.optJSONArray("entitlements") ?: JSONArray()
        val ents = mutableListOf<PublicEntitlement>()
        for (i in 0 until arr.length()) {
            decodeEntitlement(arr.optJSONObject(i))?.let { ents.add(it) }
        }
        return EntitlementSnapshot(devUid, ents, lastUpdated, lastFailedAt)
    }

    private fun decodeEntitlement(obj: JSONObject?): PublicEntitlement? {
        if (obj == null) return null
        val key = obj.optString("key").takeIf { it.isNotEmpty() } ?: return null
        val src = obj.optJSONObject("source") ?: return null
        val rail = AuditRail.fromWire(src.optString("rail")) ?: return null
        return PublicEntitlement(
            key = key,
            isActive = obj.optBoolean("isActive", false),
            validUntil = if (obj.has("validUntil")) obj.optLong("validUntil") else null,
            source = PublicEntitlement.EntitlementSource(
                rail = rail,
                productId = src.optString("productId"),
                subscriptionId = src.optString("subscriptionId"),
            ),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}
