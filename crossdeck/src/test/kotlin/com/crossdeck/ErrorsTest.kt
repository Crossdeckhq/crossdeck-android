package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade error envelope contract: every error the SDK surfaces
 * — thrown from public APIs, returned to callbacks, or raised inside
 * the queue — uses the same four-field shape (type, code, message,
 * requestId) so consumer handling is identical regardless of origin.
 *
 * Wire-vocabulary contract: 5xx maps to INTERNAL_ERROR (not
 * API_ERROR / UNKNOWN — those tokens never appear on the backend
 * wire as of v1.4.0).
 *
 * Retry-After parsing: spec allows delta-seconds OR an HTTP-date;
 * both shapes must produce a positive millisecond value, garbage
 * returns null (never crashes).
 */
class ErrorsTest {

    // ─── crossdeckErrorFromResponse ───────────────────────────────

    @Test
    fun `decodes the structured envelope from server JSON`() {
        val body = """{"error":{"type":"invalid_request_error","code":"missing_event_name","message":"event 'name' is required","request_id":"req_abc"}}"""
        val err = crossdeckErrorFromResponse(400, requestIdHeader = "ignored_header", body = body)
        assertEquals(CrossdeckErrorType.INVALID_REQUEST, err.type)
        assertEquals("missing_event_name", err.code)
        assertEquals("event 'name' is required", err.message)
        assertEquals("req_abc", err.requestId)
        assertEquals(400, err.statusCode)
    }

    @Test
    fun `falls back to status-derived defaults when body is garbage`() {
        val err = crossdeckErrorFromResponse(500, requestIdHeader = "req_from_header", body = "<html>Bad Gateway</html>")
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, err.type)
        assertEquals("server_error", err.code)
        assertEquals("req_from_header", err.requestId)
        assertEquals(500, err.statusCode)
    }

    @Test
    fun `reads request id from header when body has none`() {
        val body = """{"error":{"type":"rate_limit_error","code":"rate_limited","message":"slow down"}}"""
        val err = crossdeckErrorFromResponse(429, requestIdHeader = "req_from_header", body = body)
        assertEquals("req_from_header", err.requestId)
    }

    @Test
    fun `prefers body request id over header`() {
        val body = """{"error":{"type":"rate_limit_error","code":"rate_limited","message":"slow down","request_id":"req_from_body"}}"""
        val err = crossdeckErrorFromResponse(429, requestIdHeader = "req_from_header", body = body)
        assertEquals("req_from_body", err.requestId)
    }

    @Test
    fun `5xx maps to INTERNAL_ERROR not the deprecated API_ERROR token`() {
        val err = crossdeckErrorFromResponse(503, requestIdHeader = null, body = null)
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, err.type)
    }

    @Test
    fun `toString includes type, code, message, request id, status`() {
        val body = """{"error":{"type":"invalid_request_error","code":"x","message":"y","request_id":"r"}}"""
        val err = crossdeckErrorFromResponse(400, requestIdHeader = null, body = body)
        val s = err.toString()
        assertTrue(s.contains("invalid_request_error"))
        assertTrue(s.contains("x"))
        assertTrue(s.contains("y"))
        assertTrue(s.contains("r"))
        assertTrue(s.contains("400"))
    }

    // ─── fromWire ─────────────────────────────────────────────────

    @Test
    fun `fromWire returns INTERNAL_ERROR for unknown tokens`() {
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, CrossdeckErrorType.fromWire("nonsense_token"))
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, CrossdeckErrorType.fromWire(null))
    }

    @Test
    fun `fromWire round-trips every known token`() {
        for (t in CrossdeckErrorType.entries) {
            assertEquals(t, CrossdeckErrorType.fromWire(t.wireValue))
        }
    }

    // ─── parseRetryAfterHeader ────────────────────────────────────

    @Test
    fun `parseRetryAfterHeader accepts delta-seconds`() {
        assertEquals(30_000L, parseRetryAfterHeader("30"))
        assertEquals(0L, parseRetryAfterHeader("0"))
    }

    @Test
    fun `parseRetryAfterHeader returns null for garbage`() {
        assertNull(parseRetryAfterHeader("not a number"))
        assertNull(parseRetryAfterHeader(""))
        assertNull(parseRetryAfterHeader(null))
    }

    @Test
    fun `parseRetryAfterHeader rejects negative seconds`() {
        assertNull(parseRetryAfterHeader("-5"))
    }

    @Test
    fun `parseRetryAfterHeader parses an HTTP-date in the future`() {
        // Date one hour from now in HTTP format.
        val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
        val future = java.util.Date(System.currentTimeMillis() + 60L * 60L * 1_000L)
        val raw = sdf.format(future)
        val parsed = parseRetryAfterHeader(raw)
        assertNotNull(parsed)
        // Should be roughly an hour; allow generous slack for system clock + parser cost.
        assertTrue("expected ~3_600_000ms, got $parsed", parsed!! in 3_500_000L..3_700_000L)
    }
}
