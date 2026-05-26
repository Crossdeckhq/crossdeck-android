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
    private companion object {
        /** Anonymous suffix used before identify() has been called. */
        private const val ANON_SUFFIX = "_anon"
        /** Index blob tracks every per-user suffix the cache has written —
         * used by `clearAll()` to scope a logout-wipe. */
        private const val INDEX_SUFFIX = "_index"
        /** Storage key prefix; per-user suffix appended at write time. */
        private const val KEY_PREFIX = "crossdeck:entitlements"
    }

    private val lock = Any()
    private var current: EntitlementSnapshot? = null
    private val subscribers: MutableMap<String, EntitlementSubscriber> = LinkedHashMap()
    /** v1.4.x bank-grade per-user storage isolation. Default suffix
     * is the anonymous slot; identify() flips this via [setUserKey]
     * to `sha256(userId)` so each user's blob lives under a
     * physically separate storage key. */
    private var currentSuffix: String = ANON_SUFFIX

    /** Current per-user storage key. */
    private val storageKey: String
        get() = "$KEY_PREFIX:$currentSuffix"

    /** Index storage key — JSON array of every suffix written. */
    private val indexKey: String
        get() = "$KEY_PREFIX:$INDEX_SUFFIX"

    init {
        val blob = storage.getString(storageKey)
        if (!blob.isNullOrEmpty()) {
            decode(blob)?.let { current = it }
        }
    }

    /**
     * v1.4.x bank-grade three-layer entitlement-cache isolation
     * (mirrors Web/RN behaviour):
     *   (a) Physical key separation — `crossdeck:entitlements:<sha256(userId)>`
     *       so a user-switch can't physically read prior user's data
     *       even if the in-memory clear is somehow skipped.
     *   (b) Unconditional in-memory clear — invoked whenever the
     *       active suffix changes, even on same-id re-identify
     *       (a tiny redundant cache rebuild is cheaper than a leak).
     *   (c) Re-hydrate from the new slot — a returning user observes
     *       their last-known-good cache immediately.
     *
     * Caller (`Crossdeck.identify()` / `reset()`) MUST invoke this
     * BEFORE the next `write()` so the persisted blob lands under
     * the right key.
     */
    public fun setUserKey(userId: String?) {
        val nextSuffix = suffixForUserId(userId)
        synchronized(lock) {
            currentSuffix = nextSuffix
            current = null
            // Re-hydrate from the new slot if anything's there.
            val blob = storage.getString(storageKey)
            if (!blob.isNullOrEmpty()) {
                decode(blob)?.let { current = it }
            }
        }
        notifyAll(current)
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
            storage.setString(storageKey, encode(snapshot))
            recordSuffixInIndex(currentSuffix)
        }
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

    /**
     * Wipe the CURRENT user's slot only — in-memory + the active
     * per-user storage key. Used internally when a single user's
     * cache needs to be invalidated (e.g. on `getEntitlements()`
     * failure recovery). The full-logout path is [[clearAll]].
     */
    public fun clear() {
        synchronized(lock) {
            if (current == null) return@synchronized
            current = null
            storage.remove(storageKey)
            removeSuffixFromIndex(currentSuffix)
        }
        notifyAll(null)
    }

    /**
     * Logout-grade wipe — bank-grade contract: removes EVERY per-
     * user entitlement slot the SDK has ever written on this
     * device, then clears the index. Used by `Crossdeck.reset()`
     * so a logout on a shared device can never leave another
     * user's entitlements readable.
     *
     * After clearAll(), the cache is back to anonymous + empty.
     */
    public fun clearAll() {
        synchronized(lock) {
            val suffixes = readIndex()
            for (suffix in suffixes) {
                storage.remove("$KEY_PREFIX:$suffix")
            }
            // Also remove the anonymous slot explicitly — it may not
            // have been indexed if cleared before its first write.
            storage.remove("$KEY_PREFIX:$ANON_SUFFIX")
            storage.remove(indexKey)
            current = null
            currentSuffix = ANON_SUFFIX
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

    // ---- v1.4.x per-user storage isolation: index + suffix helpers ----

    /** Derive a stable suffix for a developerUserId via SHA-256.
     * Reuses the IdempotencyKey.sha256Hex helper so we ship a
     * single hash implementation in the SDK. Empty / null userId
     * lands in the anonymous slot. */
    private fun suffixForUserId(userId: String?): String {
        if (userId.isNullOrEmpty()) return ANON_SUFFIX
        return IdempotencyKey.sha256Hex(userId)
    }

    /** Read the index of all per-user suffixes the SDK has written. */
    private fun readIndex(): List<String> {
        val raw = storage.getString(indexKey) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Add a suffix to the persisted index. Idempotent. */
    private fun recordSuffixInIndex(suffix: String) {
        val existing = readIndex()
        if (existing.contains(suffix)) return
        val arr = JSONArray()
        for (s in existing) arr.put(s)
        arr.put(suffix)
        storage.setString(indexKey, arr.toString())
    }

    /** Remove a suffix from the persisted index. No-op if absent. */
    private fun removeSuffixFromIndex(suffix: String) {
        val existing = readIndex()
        val next = existing.filter { it != suffix }
        if (next.size == existing.size) return
        if (next.isEmpty()) {
            storage.remove(indexKey)
        } else {
            val arr = JSONArray()
            for (s in next) arr.put(s)
            storage.setString(indexKey, arr.toString())
        }
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
