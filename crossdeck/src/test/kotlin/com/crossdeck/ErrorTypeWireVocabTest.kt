package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6.1 contract test — error type wire vocabulary alignment.
 *
 * Pre-v1.4.0 the SDK declared `API_ERROR = "api_error"` and
 * `UNKNOWN = "unknown_error"` but the backend NEVER emitted those
 * tokens (`backend/src/api/v1-errors.ts:23-28`). Native code
 * pattern-matching on `API_ERROR` matched only the SDK-synthesised
 * fallback, never a real backend envelope — a silent contract
 * drift that v1.4.0 corrects:
 *
 *   - Adds `INTERNAL_ERROR` aligned with the backend's wire token.
 *   - Adds `CONFIGURATION_ERROR` for parity with the TS SDKs.
 *   - Deprecates `API_ERROR` and `UNKNOWN` (kept for source-compat
 *     but not emitted by any new code path).
 */
class ErrorTypeWireVocabTest {

    @Test
    fun `backend 500 response parses to INTERNAL_ERROR`() {
        // Mirrors backend/src/api/v1-errors.ts:78 — internal_error
        // is the canonical 5xx wire token.
        val body = """{"error":{"type":"internal_error","code":"server_error","message":"oops","request_id":"req_x"}}"""
        val err = crossdeckErrorFromResponse(500, "req_x", body)
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, err.type)
        assertEquals("server_error", err.code)
        assertEquals(500, err.statusCode)
        assertEquals("req_x", err.requestId)
    }

    @Test
    fun `5xx with no body falls back to INTERNAL_ERROR by status`() {
        // Status-fallback path (when the body is missing or not
        // parseable). Pre-v1.4.0 returned API_ERROR — which would
        // never match the actual backend envelope token.
        val err = crossdeckErrorFromResponse(503, null, null)
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, err.type)
        assertEquals(503, err.statusCode)
    }

    @Test
    fun `unrecognised status maps to INTERNAL_ERROR instead of deprecated UNKNOWN`() {
        // 418 (I'm a teapot) — pre-v1.4.0 mapped to UNKNOWN.
        // The deprecated UNKNOWN case has no wire equivalent, so
        // the fallback is the closest honest mapping.
        val err = crossdeckErrorFromResponse(418, null, "teapot")
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, err.type)
    }

    @Test
    fun `fromWire(internal_error) returns INTERNAL_ERROR`() {
        assertEquals(
            CrossdeckErrorType.INTERNAL_ERROR,
            CrossdeckErrorType.fromWire("internal_error"),
        )
    }

    @Test
    fun `fromWire(configuration_error) returns CONFIGURATION_ERROR`() {
        // Parity with TS SDKs — the type is client-side fail-fast,
        // but the wire value is well-defined and recognised when
        // the SDK forwards an error envelope verbatim (e.g. from
        // a server-side proxy that synthesizes one).
        assertEquals(
            CrossdeckErrorType.CONFIGURATION_ERROR,
            CrossdeckErrorType.fromWire("configuration_error"),
        )
    }

    @Test
    fun `fromWire(unknown wire token) falls back to INTERNAL_ERROR`() {
        // Pre-v1.4.0 fell back to UNKNOWN. New fallback is
        // INTERNAL_ERROR — it's the closest semantic to "we don't
        // know what this is".
        assertEquals(
            CrossdeckErrorType.INTERNAL_ERROR,
            CrossdeckErrorType.fromWire("future_error_type"),
        )
    }

    @Test
    fun `deprecated API_ERROR + UNKNOWN still parse from wire for source-compat`() {
        // The cases are deprecated but not REMOVED — old SDK
        // versions or test fixtures may still emit these tokens.
        // Parsing them must succeed; the deprecation only blocks
        // new code paths from emitting them.
        @Suppress("DEPRECATION")
        assertEquals(
            CrossdeckErrorType.API_ERROR,
            CrossdeckErrorType.fromWire("api_error"),
        )
        @Suppress("DEPRECATION")
        assertEquals(
            CrossdeckErrorType.UNKNOWN,
            CrossdeckErrorType.fromWire("unknown_error"),
        )
    }
}
