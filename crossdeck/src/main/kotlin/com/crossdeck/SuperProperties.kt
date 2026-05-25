// Super-properties — small dictionary auto-attached to every event.
//
// Same surface as Web/Node/RN/Swift:
//   register, registerOnce, unregister, clear, snapshot
//
// Persisted as a single JSON blob (`super_props` key — matches the
// platform convention). Blob is rewritten in full on every
// mutation; super-properties are O(10) items in normal use so a
// partial-patch protocol would be over-engineered.

package com.crossdeck

import org.json.JSONObject

public class SuperProperties(private val storage: KeyValueStorage) {
    private val storageKey = "super_props"
    private val lock = Any()
    private val values: MutableMap<String, String> = LinkedHashMap()

    init {
        val blob = storage.getString(storageKey)
        if (!blob.isNullOrEmpty()) {
            try {
                val obj = JSONObject(blob)
                obj.keys().forEach { key ->
                    val v = obj.optString(key, null)
                    if (v != null) values[key] = v
                }
            } catch (_: Exception) {
                // Malformed blob — start clean. Cross-SDK
                // migration is welcome to break here; the cost is
                // a one-launch property reset, not data loss.
            }
        }
    }

    public fun register(key: String, value: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            values[key] = value
            persist()
        }
    }

    public fun registerOnce(key: String, value: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            if (values.containsKey(key)) return@synchronized
            values[key] = value
            persist()
        }
    }

    public fun unregister(key: String) {
        synchronized(lock) {
            if (values.remove(key) != null) persist()
        }
    }

    public fun clear() {
        synchronized(lock) {
            if (values.isEmpty()) return@synchronized
            values.clear()
            storage.remove(storageKey)
        }
    }

    public fun snapshot(): Map<String, String> = synchronized(lock) { values.toMap() }

    private fun persist() {
        val obj = JSONObject()
        for ((k, v) in values) obj.put(k, v)
        storage.setString(storageKey, obj.toString())
    }
}
