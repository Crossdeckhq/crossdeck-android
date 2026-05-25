// Persistent KV storage abstraction.
//
// The SDK persists four classes of state across process restarts:
//
//   * Identity (anon_id, developer_user_id, cdcust_id)
//   * Super-properties (single JSON blob)
//   * Groups (single JSON blob)
//   * Entitlement cache (single JSON blob)
//   * Event queue (buffer + pendingBatch)
//
// On Android the default is `SharedPreferences` — available on
// every API level we target, survives app restarts, on-device-only
// (no iCloud-equivalent sync). Hidden behind a `KeyValueStorage`
// interface so tests + restricted environments can inject a
// memory-only fallback.

package com.crossdeck

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

public interface KeyValueStorage {
    public fun getString(key: String): String?
    public fun setString(key: String, value: String)
    public fun remove(key: String)
}

/**
 * SharedPreferences-backed storage with a Crossdeck-scoped prefs
 * file (`crossdeck.kv`) so we never collide with the consumer
 * app's own prefs.
 *
 * Writes go through `apply()` (async-commit semantics): in-memory
 * state updates synchronously but the disk write is deferred. The
 * right trade-off for an analytics SDK — we never block the caller
 * on disk IO; a crash before the disk write doesn't lose data
 * because the queue rehydrates on next launch from the same path.
 */
public class SharedPreferencesStorage(context: Context, prefsName: String = "crossdeck.kv") :
    KeyValueStorage {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * In-memory fallback. Used in JVM unit tests, App Extensions
 * without a Context, or consumers who deliberately opt out of
 * cross-launch persistence. Thread-safe via ConcurrentHashMap.
 */
public class MemoryStorage : KeyValueStorage {
    private val map = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun setString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
