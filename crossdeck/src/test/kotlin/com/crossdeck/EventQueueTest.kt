package com.crossdeck

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bank-grade event queue contract — the heart of the SDK.
 * Mirrors Swift's EventQueueIntegrationTests + parts of
 * HttpRetryAndIdempotencyTests:
 *
 *   - NEVER LOSE A BUFFERED EVENT: every enqueue persists to
 *     storage before returning.
 *   - REHYDRATION: a fresh queue bound to the same storage
 *     recovers the prior buffer + pending batch + retry state.
 *   - OVERFLOW: max buffer drops oldest, never blocks the caller.
 *   - SUCCESS path: a 2xx clears the pending slot.
 *   - 4xx HARD STOP: a permanent failure invokes onPermanentFailure
 *     and removes the batch from the pending slot.
 *   - IDEMPOTENCY KEY: same pending batch reuses its Idempotency-Key
 *     across retries (server-side dedup gate).
 *   - RETRY-AFTER: a 429 with retryAfterMs schedules nextRetryAtMs.
 *
 * Uses a fake HttpClient that records every call and returns a
 * scripted outcome — no real network, no Android framework.
 */
class EventQueueTest {

    // ─── Fakes ────────────────────────────────────────────────────

    private class FakeStorage : KeyValueStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun setString(key: String, value: String) { data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    private class ScriptedHttp : HttpClient(
        baseUrl = "https://api.cross-deck.test",
        publicKey = "cd_pub_test",
        packageName = "com.crossdeck.test",
    ) {
        data class Call(val body: ByteArray, val idempotencyKey: String)
        val calls = mutableListOf<Call>()
        var next: (Int) -> HttpSendOutcome = { _ -> success() }
        val callCount = AtomicInteger(0)

        override fun sendEvents(body: ByteArray, idempotencyKey: String): HttpSendOutcome {
            val n = callCount.incrementAndGet()
            calls += Call(body, idempotencyKey)
            return next(n - 1)
        }

        companion object {
            fun success(): HttpSendOutcome = HttpSendOutcome(
                kind = HttpSendOutcome.Kind.SUCCESS,
                envelope = HttpResponseEnvelope(202, body = "{}", retryAfterMs = null, requestId = "req_ok"),
                error = null,
            )

            fun permanent(status: Int = 400, code: String = "bad_request"): HttpSendOutcome = HttpSendOutcome(
                kind = HttpSendOutcome.Kind.PERMANENT,
                envelope = HttpResponseEnvelope(status, body = """{"error":{"type":"invalid_request_error","code":"$code","message":"bad"}}""", retryAfterMs = null, requestId = "req_bad"),
                error = CrossdeckError(CrossdeckErrorType.INVALID_REQUEST, code, "bad", requestId = "req_bad", statusCode = status),
            )

            fun retryable(status: Int = 503, retryAfterMs: Long? = null): HttpSendOutcome = HttpSendOutcome(
                kind = HttpSendOutcome.Kind.RETRYABLE,
                envelope = HttpResponseEnvelope(status, body = null, retryAfterMs = retryAfterMs, requestId = "req_retry"),
                error = null,
            )
        }
    }

    private fun wireEvent(name: String = "evt"): WireEvent = WireEvent(
        id = "evt_" + UUID.randomUUID().toString().replace("-", ""),
        name = name,
        timestampMs = System.currentTimeMillis(),
        properties = mapOf("k" to "v"),
        anonymousId = "anon_abc",
        developerUserId = null,
        crossdeckCustomerId = null,
    )

    private fun env(): BatchEnvelope = BatchEnvelope(appId = "app_test", environment = Environment.PRODUCTION)

    // ─── Tests ────────────────────────────────────────────────────

    @Test
    fun `enqueue persists to storage before returning`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp()
        // Force batchSize huge so a single enqueue does not trigger a flush.
        val q = EventQueue(http, storage, env(), config = EventQueueConfig(batchSize = 100))
        q.enqueue(wireEvent("first"))
        assertNotNull("buffer must be persisted on enqueue", storage.data["queue.buffer.v1"])
        assertTrue(storage.data["queue.buffer.v1"]!!.contains("first"))
    }

    @Test
    fun `rehydration restores buffered events into a fresh queue`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp()
        val first = EventQueue(http, storage, env(), config = EventQueueConfig(batchSize = 100))
        first.enqueue(wireEvent("alpha"))
        first.enqueue(wireEvent("beta"))

        val reopened = EventQueue(ScriptedHttp(), storage, env(), config = EventQueueConfig(batchSize = 100))
        val s = reopened.stats()
        assertEquals(2, s.buffered)
        assertEquals(0, s.pending)
    }

    @Test
    fun `stats reports the live buffered count`() = runTest {
        val q = EventQueue(ScriptedHttp(), FakeStorage(), env(), config = EventQueueConfig(batchSize = 100))
        q.enqueue(wireEvent("a"))
        q.enqueue(wireEvent("b"))
        q.enqueue(wireEvent("c"))
        assertEquals(3, q.stats().buffered)
    }

    @Test
    fun `overflow drops oldest events when buffer hits the cap`() = runTest {
        val storage = FakeStorage()
        val cap = 5
        val q = EventQueue(
            http = ScriptedHttp(),
            storage = storage,
            envelope = env(),
            // batchSize must exceed cap so we don't auto-flush during the load.
            config = EventQueueConfig(batchSize = 1_000, maxBufferSize = cap),
        )
        repeat(cap + 3) { i -> q.enqueue(wireEvent("n$i")) }
        val s = q.stats()
        assertEquals(cap, s.buffered)
        // The most-recent 5 should survive (n3..n7); the first 3 (n0..n2) dropped.
        val persisted = storage.data["queue.buffer.v1"]!!
        assertTrue("expected newest event n7 to survive: $persisted", persisted.contains("n7"))
        assertTrue("expected oldest event n0 to be dropped", !persisted.contains("\"n0\""))
    }

    @Test
    fun `2xx success clears the pending slot and persistence`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp()
        val q = EventQueue(http, storage, env(), config = EventQueueConfig(batchSize = 1))
        // batchSize=1 means enqueue → immediate flush.
        q.enqueue(wireEvent("flushme"))
        assertEquals(1, http.callCount.get())
        val s = q.stats()
        assertEquals(0, s.buffered)
        assertEquals(0, s.pending)
        assertNull(storage.data["queue.pending.v1"])
    }

    @Test
    fun `4xx permanent failure invokes onPermanentFailure and drops the batch`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp().apply { next = { _ -> ScriptedHttp.permanent(400, "bad_request") } }
        val captured = AtomicReference<Pair<List<WireEvent>, CrossdeckError>?>(null)
        val q = EventQueue(
            http = http,
            storage = storage,
            envelope = env(),
            onPermanentFailure = { events, err -> captured.set(events to err) },
            config = EventQueueConfig(batchSize = 1),
        )
        q.enqueue(wireEvent("doomed"))

        // Permanent handler is dispatched onto IO scope — give it a beat.
        // runTest uses a virtual clock; advance it via a small yield.
        kotlinx.coroutines.delay(50)

        val s = q.stats()
        assertEquals(0, s.pending)
        assertNull("pending slot must be cleared on permanent failure", storage.data["queue.pending.v1"])
        val handled = captured.get()
        assertNotNull("onPermanentFailure must fire for a 4xx", handled)
        assertEquals(1, handled!!.first.size)
        assertEquals("bad_request", handled.second.code)
    }

    @Test
    fun `retryable response keeps the pending batch and schedules a retry`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp().apply {
            // First call retryable with Retry-After 5s; subsequent we do not exercise.
            next = { _ -> ScriptedHttp.retryable(status = 503, retryAfterMs = 5_000L) }
        }
        val q = EventQueue(http, storage, env(), config = EventQueueConfig(batchSize = 1))
        q.enqueue(wireEvent("retry-me"))

        val s = q.stats()
        assertEquals("pending should still hold the batch awaiting retry", 1, s.pending)
        assertEquals(1, s.attemptsForPending)
        assertNotNull("nextRetryAtMs must be set", s.nextRetryAtMs)
        assertNotNull("pending must be persisted across retries", storage.data["queue.pending.v1"])
    }

    @Test
    fun `pending batch reuses the same Idempotency-Key across retries`() = runTest {
        val storage = FakeStorage()
        val http = ScriptedHttp().apply {
            next = { i ->
                if (i == 0) ScriptedHttp.retryable(status = 503, retryAfterMs = 0L)
                else ScriptedHttp.success()
            }
        }
        val q = EventQueue(http, storage, env(), config = EventQueueConfig(batchSize = 1))
        q.enqueue(wireEvent("idem"))
        // Second flush — should pick up the same pendingBatch and reuse the key.
        q.flush()

        assertEquals("expected two HTTP calls (retry + success)", 2, http.callCount.get())
        assertEquals(
            "Idempotency-Key MUST be identical across retries of the same batch",
            http.calls[0].idempotencyKey,
            http.calls[1].idempotencyKey,
        )
        assertEquals(0, q.stats().pending)
    }

    @Test
    fun `persistAll writes buffer and pending to storage`() = runTest {
        val storage = FakeStorage()
        val q = EventQueue(ScriptedHttp(), storage, env(), config = EventQueueConfig(batchSize = 100))
        q.enqueue(wireEvent("a"))
        q.persistAll()
        assertNotNull(storage.data["queue.buffer.v1"])
    }
}
