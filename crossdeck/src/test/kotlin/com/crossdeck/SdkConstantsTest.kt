package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank-grade SDK identity contract: the wire-format `sdk`
 * identifier MUST be populated and follow `@cross-deck/<rail>`
 * shape; the `version` MUST be semver. Server-side debugging
 * tools rely on parsing these.
 */
class SdkConstantsTest {

    @Test
    fun `SDK name is the canonical @cross-deck slug`() {
        assertEquals("@cross-deck/android", Sdk.NAME)
    }

    @Test
    fun `SDK version looks like semver`() {
        assertTrue("expected semver, got '${Sdk.VERSION}'", Sdk.VERSION.matches(Regex("^\\d+\\.\\d+\\.\\d+(?:-[a-zA-Z0-9.-]+)?$")))
    }
}
