package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1.1 contract test — Android billing-sync MUST surface a
 * typed error on every non-2xx outcome. Replaces the prior silent
 * debug-log swallow at Crossdeck.kt:1230-1234.
 *
 * The mapper is intentionally pure so the contract is verifiable
 * in JVM unit tests with no Android Application context.
 */
class BillingSyncOutcomeMapperTest {

    @Test
    fun `2xx outcome maps to Result success`() {
        val outcome = HttpSendOutcome(
            kind = HttpSendOutcome.Kind.SUCCESS,
            envelope = HttpResponseEnvelope(
                statusCode = 200,
                body = """{"ok":true}""",
                retryAfterMs = null,
                requestId = "req_ok",
            ),
            error = null,
        )

        val result = mapBillingSyncOutcome(outcome)

        assertTrue("SUCCESS must produce Result.success", result.isSuccess)
        assertNull("Result.success carries no exception", result.exceptionOrNull())
    }

    @Test
    fun `400 permanent outcome with backend envelope propagates typed CrossdeckError`() {
        val backendErr = CrossdeckError(
            type = CrossdeckErrorType.INVALID_REQUEST,
            code = "google_not_supported",
            message = "Google Play purchase forwarding is not yet supported.",
            requestId = "req_400",
            statusCode = 400,
        )
        val outcome = HttpSendOutcome(
            kind = HttpSendOutcome.Kind.PERMANENT,
            envelope = HttpResponseEnvelope(
                statusCode = 400,
                body = """{"error":{"type":"invalid_request_error","code":"google_not_supported"}}""",
                retryAfterMs = null,
                requestId = "req_400",
            ),
            error = backendErr,
        )

        val result = mapBillingSyncOutcome(outcome)

        assertTrue("PERMANENT must produce Result.failure", result.isFailure)
        val thrown = result.exceptionOrNull()
        assertTrue("Must be a typed CrossdeckError", thrown is CrossdeckError)
        val typed = thrown as CrossdeckError
        assertEquals(CrossdeckErrorType.INVALID_REQUEST, typed.type)
        assertEquals("google_not_supported", typed.code)
        assertEquals(400, typed.statusCode)
        assertEquals("req_400", typed.requestId)
    }

    @Test
    fun `5xx retryable outcome with backend envelope propagates typed CrossdeckError`() {
        val backendErr = CrossdeckError(
            type = CrossdeckErrorType.INTERNAL_ERROR,
            code = "server_error",
            message = "Server error — Crossdeck will retry.",
            statusCode = 503,
        )
        val outcome = HttpSendOutcome(
            kind = HttpSendOutcome.Kind.RETRYABLE,
            envelope = HttpResponseEnvelope(
                statusCode = 503,
                body = null,
                retryAfterMs = 5_000L,
                requestId = null,
            ),
            error = backendErr,
        )

        val result = mapBillingSyncOutcome(outcome)

        assertTrue(result.isFailure)
        val typed = result.exceptionOrNull() as CrossdeckError
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, typed.type)
        assertEquals(503, typed.statusCode)
    }

    @Test
    fun `non-success with null error synthesises a typed fallback`() {
        // Defence-in-depth — even if a future HttpSendOutcome bug
        // strips the typed envelope, the mapper MUST emit a typed
        // failure. The fallback code is the contract token callers
        // pattern-match on.
        val outcome = HttpSendOutcome(
            kind = HttpSendOutcome.Kind.PERMANENT,
            envelope = HttpResponseEnvelope(
                statusCode = 418,
                body = "I'm a teapot",
                retryAfterMs = null,
                requestId = null,
            ),
            error = null,
        )

        val result = mapBillingSyncOutcome(outcome)

        assertTrue(result.isFailure)
        val typed = result.exceptionOrNull() as CrossdeckError
        assertEquals(CrossdeckErrorType.INTERNAL_ERROR, typed.type)
        assertEquals("auto_billing_sync_failed", typed.code)
        assertEquals(418, typed.statusCode)
        assertNotNull(typed.message)
    }

    @Test
    fun `null envelope on non-success still produces typed failure`() {
        // The IOException path in HttpClient.request returns
        // kind=RETRYABLE, envelope=null, error=NETWORK CrossdeckError.
        // Mapper passes the typed network error through; verifies
        // that the "synthesise fallback" branch only fires when
        // BOTH envelope and error are absent.
        val networkErr = CrossdeckError(
            type = CrossdeckErrorType.NETWORK,
            code = "io_error",
            message = "Connection reset by peer.",
        )
        val outcome = HttpSendOutcome(
            kind = HttpSendOutcome.Kind.RETRYABLE,
            envelope = null,
            error = networkErr,
        )

        val result = mapBillingSyncOutcome(outcome)

        assertTrue(result.isFailure)
        val typed = result.exceptionOrNull() as CrossdeckError
        assertEquals(CrossdeckErrorType.NETWORK, typed.type)
        assertEquals("io_error", typed.code)
    }
}
