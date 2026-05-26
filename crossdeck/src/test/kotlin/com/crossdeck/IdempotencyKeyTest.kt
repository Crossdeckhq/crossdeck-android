package com.crossdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.2.c contract tests — Android deterministic Idempotency-Key.
 *
 * Pinned cross-SDK oracle: `deriveForPurchase("apple",
 * "eyJ.jws.sig", null)` MUST equal
 * `"a66b1640-efaf-bb4d-1261-6650033bf111"` on every SDK. The same
 * vector is asserted in:
 *   - sdks/web/tests/idempotency-key.test.ts
 *   - sdks/node/tests/idempotency-key.test.ts
 *   - sdks/react-native/tests/idempotency-key.test.ts
 *   - sdks/swift/Tests/CrossdeckTests/IdempotencyKeyTests.swift
 *
 * A regression here breaks the wire-protocol parity Stripe-grade
 * idempotency depends on.
 */
class IdempotencyKeyTest {

    // ----- Cross-SDK oracle -----

    @Test
    fun `cross-SDK oracle for apple JWS`() {
        // Canonical vector. Same input on every SDK = this UUID.
        // Pin computed via:
        //   node -e "const c=require('crypto');console.log(c.createHash('sha256').update('crossdeck:purchases/sync:apple:eyJ.jws.sig').digest('hex'))"
        // = a66b1640efafbb4d12616650033bf111509f0313643d697a1e6963184b31be51
        val key = IdempotencyKey.deriveForPurchase(
            rail = "apple",
            signedTransactionInfo = "eyJ.jws.sig",
        )
        assertEquals("a66b1640-efaf-bb4d-1261-6650033bf111", key)
    }

    // ----- Determinism -----

    @Test
    fun `same input produces same key`() {
        val a = IdempotencyKey.deriveForPurchase(
            rail = "apple",
            signedTransactionInfo = "eyJ.jws.sig",
        )
        val b = IdempotencyKey.deriveForPurchase(
            rail = "apple",
            signedTransactionInfo = "eyJ.jws.sig",
        )
        assertEquals(a, b)
        assertNotNull(a)
    }

    @Test
    fun `different inputs produce different keys`() {
        val a = IdempotencyKey.deriveForPurchase(rail = "apple", signedTransactionInfo = "eyJ.first")
        val b = IdempotencyKey.deriveForPurchase(rail = "apple", signedTransactionInfo = "eyJ.second")
        assertNotEquals(a, b)
    }

    // ----- Rail handling -----

    @Test
    fun `google rail uses purchaseToken`() {
        val key = IdempotencyKey.deriveForPurchase(
            rail = "google",
            purchaseToken = "play-token-abc",
        )
        assertNotNull(key)
        assertTrue(
            "UUID shape",
            key!!.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")),
        )
    }

    @Test
    fun `rail namespacing prevents cross-rail collisions`() {
        // Defence-in-depth: a JWS string that happens to share
        // bytes with a Google token must NOT produce the same key.
        val apple = IdempotencyKey.deriveForPurchase(
            rail = "apple",
            signedTransactionInfo = "shared-bytes",
        )
        val google = IdempotencyKey.deriveForPurchase(
            rail = "google",
            purchaseToken = "shared-bytes",
        )
        assertNotEquals(apple, google)
    }

    // ----- Failure modes -----

    @Test
    fun `missing identifier returns null - never silent random fallback`() {
        assertNull(IdempotencyKey.deriveForPurchase(rail = "apple"))
        assertNull(IdempotencyKey.deriveForPurchase(rail = "google"))
        assertNull(IdempotencyKey.deriveForPurchase(rail = "stripe"))
    }

    @Test
    fun `empty identifier returns null`() {
        assertNull(IdempotencyKey.deriveForPurchase(rail = "apple", signedTransactionInfo = ""))
    }

    // ----- formatAsUuid -----

    @Test
    fun `formatAsUuid produces 8-4-4-4-12 shape`() {
        assertEquals(
            "01234567-89ab-cdef-0123-456789abcdef",
            IdempotencyKey.formatAsUuid("0123456789abcdef0123456789abcdef0123456789abcdef"),
        )
    }

    // ----- sha256Hex -----

    @Test
    fun `sha256Hex matches FIPS reference vector for 'abc'`() {
        // FIPS 180-4 reference: SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IdempotencyKey.sha256Hex("abc"),
        )
    }
}
