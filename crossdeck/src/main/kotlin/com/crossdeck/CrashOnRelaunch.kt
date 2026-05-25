package com.crossdeck

import java.io.File
import org.json.JSONObject

/**
 * Crash-on-relaunch — persisted fatal exception capture.
 *
 * The Swift SDK gets this "for free" because its persistent event
 * queue writes to UserDefaults on every enqueue, so a fatal that
 * arrives mid-enqueue still lands on disk. Android's EventQueue
 * (per the audit) does NOT necessarily have the same guarantee:
 * the uncaught-exception handler builds a wire event, but the
 * enqueue path runs on a process that's already about to die, so
 * the file write is racy and the event often vanishes.
 *
 * Fix: stash a minimal crash record to a dedicated file BEFORE
 * forwarding to the prior handler. Next launch, the SDK reads the
 * file, posts the event through the normal pipeline, then deletes
 * the file. Fatal crashes — the events most needed for diagnosis —
 * are now durable across process death.
 *
 * File location: `<app filesDir>/crossdeck-fatal.json`. Single-
 * record file (only the most recent fatal); successive crashes
 * before a relaunch are coalesced. Worth the tradeoff: writing
 * a separate file per crash bloats stats with retry-spam from
 * apps that crash-loop on launch.
 *
 * Bank-grade contract:
 *   * Synchronous write. The fatal handler MUST complete before
 *     the process dies; async / buffered writes risk being torn.
 *   * Tiny payload — exception type, message, top 50 stack frames.
 *     No breadcrumbs (those are in-memory and unrecoverable).
 *   * Read+delete on next start, never on subsequent start (so a
 *     parsing failure doesn't loop-emit).
 */
internal class CrashOnRelaunch(
    private val filesDir: File,
) {
    private val crashFile: File by lazy {
        File(filesDir, "crossdeck-fatal.json")
    }

    /**
     * Write a minimal crash record. Called from the uncaught-
     * exception handler. Synchronous + best-effort — if the disk
     * is full / read-only, we swallow silently rather than risk
     * adding noise to the consumer's own crash reporting.
     */
    fun writeFatal(throwable: Throwable, sessionId: String?, developerUserId: String?) {
        try {
            val payload = JSONObject().apply {
                put("type", throwable.javaClass.name)
                put("message", throwable.message ?: "")
                put("timestamp", System.currentTimeMillis())
                if (sessionId != null) put("sessionId", sessionId)
                if (developerUserId != null) put("developerUserId", developerUserId)
                val frames = throwable.stackTrace.take(50).joinToString("\n") { it.toString() }
                put("stack", frames)
            }
            crashFile.parentFile?.mkdirs()
            crashFile.writeText(payload.toString())
        } catch (_: Throwable) {
            // Best-effort. If the disk is full, the SDK isn't the
            // place to surface that.
        }
    }

    /**
     * Read any persisted crash record, returning a parsed map,
     * and delete the file. Called on `start()` after the queue
     * is rehydrated; the consumer's track() pipeline ships the
     * resulting event through the normal route.
     *
     * Returns null if no file exists or parsing fails — we
     * deliberately don't retry on parse failure to avoid an
     * infinite emit loop if the file is corrupt.
     */
    fun consumePersistedFatal(): Map<String, Any?>? {
        return try {
            if (!crashFile.exists()) return null
            val text = crashFile.readText()
            crashFile.delete()
            if (text.isEmpty()) return null
            val json = JSONObject(text)
            val out = mutableMapOf<String, Any?>()
            json.keys().forEach { key ->
                out[key] = json.opt(key)
            }
            out
        } catch (_: Throwable) {
            // Best-effort cleanup if parse failed.
            try { crashFile.delete() } catch (_: Throwable) {}
            null
        }
    }
}
