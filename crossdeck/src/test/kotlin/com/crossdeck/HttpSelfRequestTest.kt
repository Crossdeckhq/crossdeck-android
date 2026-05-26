package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade error-capture loop guard: errors emitted by the
 * SDK's own HTTP calls against its own ingest endpoint must be
 * skipped. Without this check, an outage in /events triggers an
 * error capture that calls /events to record the error — which
 * fails — recursively. `isSelfRequest` is the gate that breaks
 * the loop; this test pins the case-insensitive matching the
 * Swift SDK enforces.
 */
class HttpSelfRequestTest {

    @Test
    fun `extractSelfHostname lowercases the host`() {
        assertEquals("api.cross-deck.com", extractSelfHostname("https://API.CROSS-DECK.com/v1"))
    }

    @Test
    fun `extractSelfHostname returns null for malformed URL`() {
        assertNull(extractSelfHostname("not a url"))
    }

    @Test
    fun `isSelfRequest matches case-insensitively`() {
        assertTrue(isSelfRequest("https://API.CROSS-DECK.COM/v1/events", "api.cross-deck.com"))
    }

    @Test
    fun `isSelfRequest returns false when selfHostname is null`() {
        assertFalse(isSelfRequest("https://api.cross-deck.com/v1/events", null))
    }

    @Test
    fun `isSelfRequest returns false for a different host`() {
        assertFalse(isSelfRequest("https://example.com/anything", "api.cross-deck.com"))
    }

    @Test
    fun `isSelfRequest returns false for a malformed candidate URL`() {
        assertFalse(isSelfRequest("not a url", "api.cross-deck.com"))
    }
}
