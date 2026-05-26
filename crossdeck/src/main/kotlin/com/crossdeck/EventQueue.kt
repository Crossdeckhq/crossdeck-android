// Event queue — heart of the SDK.
//
// Responsibilities, in priority order:
//
//   1) NEVER LOSE A BUFFERED EVENT. From enqueue to server-ack,
//      the event lives in either the in-memory buffer, the
//      pendingBatch slot, or the on-disk durability layer.
//
//   2) NEVER DOUBLE-INSERT. Each batch gets a stable
//      Idempotency-Key generated once on first send; server-side
//      dedup collapses retries.
//
//   3) 4xx HARD STOP. Permanent failures drain through the
//      onPermanentFailure callback and do NOT retry.
//
// The pendingBatch slot is the critical invariant. On flush start
// we MOVE the head-of-queue into pendingBatch. While the HTTP
// request is in flight new enqueue calls append to the fresh
// buffer behind it. The pending batch is removed ONLY after the
// server confirms success. A crash mid-flight leaves the pending
// batch persisted; on next launch we rehydrate and re-send with
// the same idempotency key.

package com.crossdeck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

public data class WireEvent(
    /** Canonical `evt_…` prefix — backend EVENT_ID_PATTERN validates. */
    val id: String,
    val name: String,
    /** Epoch ms — matches Web/Node/RN/Swift wire shape. */
    val timestampMs: Long,
    val properties: Map<String, Any?>,
    val anonymousId: String,
    val developerUserId: String?,
    val crossdeckCustomerId: String?,
)

public typealias PermanentFailureHandler = (events: List<WireEvent>, error: CrossdeckError) -> Unit

public data class EventQueueConfig(
    var batchSize: Int = 20,
    /**
     * v1.4.0 Phase 3.3 — flush interval default parity at 2000ms
     * across every Crossdeck SDK. Pre-v1.4.0 Android used 5000ms,
     * out of step with Web/Node's 1500ms; v1.4.0 converged on
     * 2000ms (Stripe-adjacent industry norm). Per-instance
     * override stays — call sites can still tune it freely.
     */
    var flushIntervalMs: Long = 2_000L,
    var maxBufferSize: Int = 1_000,
    var retry: RetryPolicy = RetryPolicy(),
)

public data class QueueStats(
    val buffered: Int,
    val pending: Int,
    val attemptsForPending: Int,
    val nextRetryAtMs: Long?,
)

public data class BatchEnvelope(val appId: String, val environment: Environment)

private data class PendingBatch(
    val events: List<WireEvent>,
    val idempotencyKey: String,
    var attempt: Int,
    var nextRetryAtMs: Long?,
)

public class EventQueue(
    private val http: HttpClient,
    private val storage: KeyValueStorage,
    private val envelope: BatchEnvelope,
    private val logger: DebugLogger = NoopDebugLogger,
    private val onPermanentFailure: PermanentFailureHandler? = null,
    private val config: EventQueueConfig = EventQueueConfig(),
) {
    /** Storage keys — match the platform `queue.v1` convention. */
    private val bufferStorageKey = "queue.buffer.v1"
    private val pendingStorageKey = "queue.pending.v1"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val buffer: MutableList<WireEvent> = mutableListOf()
    private var pendingBatch: PendingBatch? = null
    private var nextRetryAtMs: Long? = null

    /**
     * One-shot guard for `sdk.first_event_sent`. The dashboard
     * onboarding checklist fires when it sees this signal — fire
     * exactly once per process lifetime.
     */
    private var firstEventSentFired: Boolean = false

    init {
        // Rehydrate buffer + pending from storage (best-effort —
        // malformed blobs are dropped, not fatal).
        storage.getString(bufferStorageKey)?.let { blob ->
            decodeEventsArray(blob)?.let { buffer.addAll(it) }
        }
        storage.getString(pendingStorageKey)?.let { blob ->
            decodePending(blob)?.let {
                pendingBatch = it
                nextRetryAtMs = it.nextRetryAtMs
            }
        }
        if (buffer.isNotEmpty() || pendingBatch != null) {
            logger(DebugSignal.SDK_QUEUE_RESTORED, mapOf(
                "buffered" to buffer.size.toString(),
                "pending" to (pendingBatch?.events?.size?.toString() ?: "0"),
            ))
        }
    }

    public suspend fun enqueue(event: WireEvent) {
        val shouldFlush: Boolean = mutex.withLock {
            if (buffer.size >= config.maxBufferSize) {
                val overflow = buffer.size - config.maxBufferSize + 1
                repeat(overflow) { buffer.removeAt(0) }
                logger(DebugSignal.SDK_FLUSH_PERMANENT_FAILURE, mapOf(
                    "reason" to "buffer_overflow",
                    "dropped" to overflow.toString(),
                ))
            }
            buffer.add(event)
            persistBufferLocked()
            logger(DebugSignal.SDK_QUEUE_PERSISTED, mapOf(
                "name" to event.name,
                "buffered" to buffer.size.toString(),
            ))
            buffer.size >= config.batchSize
        }
        if (shouldFlush) flush()
    }

    public suspend fun flush() {
        val batchToSend: PendingBatch = mutex.withLock {
            if (pendingBatch == null) {
                if (buffer.isEmpty()) return
                val take = minOf(config.batchSize, buffer.size)
                val head = buffer.take(take)
                repeat(take) { buffer.removeAt(0) }
                persistBufferLocked()
                pendingBatch = PendingBatch(
                    events = head,
                    idempotencyKey = makeIdempotencyKey(),
                    attempt = 0,
                    nextRetryAtMs = null,
                )
                persistPendingLocked()
            }
            val current = pendingBatch ?: return
            val when_ = current.nextRetryAtMs
            if (when_ != null && when_ > System.currentTimeMillis()) return
            current
        }

        val body = encodeBatch(batchToSend.events)
        val outcome = withContext(Dispatchers.IO) {
            http.sendEvents(body, batchToSend.idempotencyKey)
        }

        mutex.withLock {
            val current = pendingBatch ?: return@withLock
            if (current.idempotencyKey != batchToSend.idempotencyKey) return@withLock

            when (outcome.kind) {
                HttpSendOutcome.Kind.SUCCESS -> {
                    logger(DebugSignal.SDK_QUEUE_PERSISTED, mapOf(
                        "size" to current.events.size.toString(),
                    ))
                    if (!firstEventSentFired) {
                        firstEventSentFired = true
                        logger(DebugSignal.SDK_FIRST_EVENT_SENT, mapOf(
                            "size" to current.events.size.toString(),
                        ))
                    }
                    pendingBatch = null
                    storage.remove(pendingStorageKey)
                    nextRetryAtMs = null
                }
                HttpSendOutcome.Kind.PERMANENT -> {
                    val err = outcome.error ?: CrossdeckError(
                        type = CrossdeckErrorType.INVALID_REQUEST,
                        code = "permanent_failure",
                        message = "Batch rejected permanently.",
                    )
                    logger(DebugSignal.SDK_FLUSH_PERMANENT_FAILURE, mapOf(
                        "code" to err.code,
                        "status" to (outcome.envelope?.statusCode?.toString() ?: "n/a"),
                    ))
                    val events = current.events
                    pendingBatch = null
                    storage.remove(pendingStorageKey)
                    nextRetryAtMs = null
                    onPermanentFailure?.let { handler ->
                        scope.launch { handler(events, err) }
                    }
                }
                HttpSendOutcome.Kind.RETRYABLE -> {
                    current.attempt += 1
                    val retryAfter = outcome.envelope?.retryAfterMs
                    val delayMs = config.retry.nextDelayMs(
                        attempt = current.attempt - 1,
                        retryAfterMs = retryAfter,
                    )
                    if (delayMs != null) {
                        val when_ = System.currentTimeMillis() + delayMs
                        current.nextRetryAtMs = when_
                        nextRetryAtMs = when_
                        persistPendingLocked()
                        logger(DebugSignal.SDK_FLUSH_RETRY_SCHEDULED, mapOf(
                            "attempt" to current.attempt.toString(),
                            "delay_ms" to delayMs.toString(),
                        ))
                    } else {
                        val err = outcome.error ?: CrossdeckError(
                            type = CrossdeckErrorType.INTERNAL_ERROR,
                            code = "retry_exhausted",
                            message = "Retry budget exhausted after ${current.attempt} attempts.",
                        )
                        logger(DebugSignal.SDK_FLUSH_PERMANENT_FAILURE, mapOf(
                            "code" to err.code,
                            "reason" to "retry_exhausted",
                        ))
                        val events = current.events
                        pendingBatch = null
                        storage.remove(pendingStorageKey)
                        nextRetryAtMs = null
                        onPermanentFailure?.let { handler ->
                            scope.launch { handler(events, err) }
                        }
                    }
                }
            }
        }

        // Drain if more buffered events accumulated during flight.
        mutex.withLock {
            if (pendingBatch == null && buffer.size >= config.batchSize) {
                scope.launch { flush() }
            }
        }
    }

    public suspend fun stats(): QueueStats = mutex.withLock {
        QueueStats(
            buffered = buffer.size,
            pending = pendingBatch?.events?.size ?: 0,
            attemptsForPending = pendingBatch?.attempt ?: 0,
            nextRetryAtMs = nextRetryAtMs,
        )
    }

    /** Persist everything to storage. Called on app-background. */
    public suspend fun persistAll(): Unit = mutex.withLock {
        persistBufferLocked()
        persistPendingLocked()
    }

    // ----- Persistence -----

    private fun persistBufferLocked() {
        if (buffer.isEmpty()) {
            storage.remove(bufferStorageKey)
            return
        }
        storage.setString(bufferStorageKey, encodeEventsArray(buffer))
    }

    private fun persistPendingLocked() {
        val p = pendingBatch
        if (p == null) {
            storage.remove(pendingStorageKey)
            return
        }
        storage.setString(pendingStorageKey, encodePending(p))
    }

    // ----- JSON wire encoder -----

    private fun encodeBatch(events: List<WireEvent>): ByteArray {
        // NorthStar §13.1 envelope (matches backend
        // v1-events-validation.ts:103 BatchEnvelope): appId,
        // environment, sdk: { name, version }, events.
        val root = JSONObject()
        root.put("appId", envelope.appId)
        root.put("environment", envelope.environment.wireValue)
        val sdk = JSONObject()
        sdk.put("name", Sdk.NAME)
        sdk.put("version", Sdk.VERSION)
        root.put("sdk", sdk)
        val arr = JSONArray()
        for (event in events) arr.put(encodeEvent(event))
        root.put("events", arr)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeEvent(event: WireEvent): JSONObject {
        // Canonical wire-event field names (camelCase) — matches
        // backend RawEvent. Drift here causes ingest validation
        // failures.
        val o = JSONObject()
        o.put("eventId", event.id)
        o.put("name", event.name)
        o.put("timestamp", event.timestampMs)
        o.put("anonymousId", event.anonymousId)
        event.developerUserId?.let { o.put("developerUserId", it) }
        event.crossdeckCustomerId?.let { o.put("crossdeckCustomerId", it) }
        o.put("properties", encodeMap(event.properties))
        return o
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeMap(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, encodeValue(v))
        return o
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeValue(v: Any?): Any {
        return when (v) {
            null -> JSONObject.NULL
            is String, is Boolean -> v
            is Number -> {
                val d = v.toDouble()
                if (d.isFinite()) v else JSONObject.NULL
            }
            is Map<*, *> -> encodeMap(v as Map<String, Any?>)
            is List<*> -> {
                val arr = JSONArray()
                for (item in v) arr.put(encodeValue(item))
                arr
            }
            else -> v.toString()
        }
    }

    // ----- Durable-state encoder (round-trips through rehydrate) -----

    private fun encodeEventsArray(events: List<WireEvent>): String {
        val arr = JSONArray()
        for (event in events) arr.put(encodeEvent(event))
        return arr.toString()
    }

    private fun decodeEventsArray(blob: String): List<WireEvent>? = try {
        val arr = JSONArray(blob)
        val out = mutableListOf<WireEvent>()
        for (i in 0 until arr.length()) {
            decodeEvent(arr.optJSONObject(i))?.let { out.add(it) }
        }
        out
    } catch (_: Exception) {
        null
    }

    private fun encodePending(p: PendingBatch): String {
        val o = JSONObject()
        o.put("idempotencyKey", p.idempotencyKey)
        o.put("attempt", p.attempt)
        if (p.nextRetryAtMs != null) o.put("nextRetryAtMs", p.nextRetryAtMs)
        val arr = JSONArray()
        for (event in p.events) arr.put(encodeEvent(event))
        o.put("events", arr)
        return o.toString()
    }

    private fun decodePending(blob: String): PendingBatch? {
        return try {
            decodePendingInner(blob)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePendingInner(blob: String): PendingBatch? {
        val o = JSONObject(blob)
        val key = o.optString("idempotencyKey").takeIf { it.isNotEmpty() } ?: return null
        val attempt = o.optInt("attempt", 0)
        val retryAt = if (o.has("nextRetryAtMs")) o.optLong("nextRetryAtMs") else null
        val arr = o.optJSONArray("events") ?: JSONArray()
        val events = mutableListOf<WireEvent>()
        for (i in 0 until arr.length()) {
            decodeEvent(arr.optJSONObject(i))?.let { events.add(it) }
        }
        return PendingBatch(events, key, attempt, retryAt)
    }

    private fun decodeEvent(obj: JSONObject?): WireEvent? {
        if (obj == null) return null
        val id = obj.optString("eventId").takeIf { it.isNotEmpty() } ?: return null
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val ts = obj.optLong("timestamp", System.currentTimeMillis())
        val anon = obj.optString("anonymousId").takeIf { it.isNotEmpty() } ?: return null
        val dev = obj.optString("developerUserId").takeIf { it.isNotEmpty() }
        val cdcust = obj.optString("crossdeckCustomerId").takeIf { it.isNotEmpty() }
        val propsObj = obj.optJSONObject("properties")
        val props = if (propsObj != null) decodeMap(propsObj) else emptyMap()
        return WireEvent(id, name, ts, props, anon, dev, cdcust)
    }

    private fun decodeMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length())
        obj.keys().forEach { key ->
            val v = obj.opt(key)
            out[key] = when (v) {
                JSONObject.NULL -> null
                is JSONObject -> decodeMap(v)
                is JSONArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        list.add(if (item == JSONObject.NULL) null else item)
                    }
                    list
                }
                else -> v
            }
        }
        return out
    }

    /**
     * Idempotency-Key prefix is the platform-wide `batch_…` —
     * matches Web/Node/RN/Swift, validated by the backend regex.
     */
    private fun makeIdempotencyKey(): String =
        "batch_" + UUID.randomUUID().toString().lowercase().replace("-", "")
}
