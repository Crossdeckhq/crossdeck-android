package com.crossdeck

import java.security.MessageDigest

/**
 * Deterministic Idempotency-Key derivation for `/purchases/sync`.
 *
 * Phase 2.2.c of bank-grade reconciliation v1.4.0. Mirrors the TS
 * + Swift implementations byte-identically — the same input (rail +
 * JWS / purchaseToken) produces the same UUID-shaped key across
 * every Crossdeck SDK, so the backend's idempotency cache short-
 * circuits regardless of which client retried.
 *
 * Algorithm:
 *   1. Extract the rail-stable identifier (Apple JWS string, Google
 *      purchaseToken).
 *   2. SHA-256 of `crossdeck:purchases/sync:<rail>:<identifier>`
 *      — rail namespacing prevents cross-rail collisions on bodies
 *      that happen to share bytes.
 *   3. Format the first 32 hex chars of the digest as a UUID shape
 *      (8-4-4-4-12). The backend treats the key as opaque so
 *      RFC 4122 version/variant bits are unnecessary —
 *      determinism is what matters.
 *
 * Pinned cross-SDK oracle: `deriveForPurchase("apple",
 * "eyJ.jws.sig", null)` MUST equal
 * `"a66b1640-efaf-bb4d-1261-6650033bf111"` on every SDK. A
 * regression there is a wire-protocol break.
 */
internal object IdempotencyKey {
    /**
     * Format a hex digest as 8-4-4-4-12 UUID shape using its first
     * 32 chars. Public so callers / tests can use the same
     * formatter the derivation does.
     */
    fun formatAsUuid(hex: String): String {
        require(hex.length >= 32) { "formatAsUuid requires at least 32 hex chars" }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** SHA-256 of [input] as a 64-char lowercase hex string. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }

    /**
     * Derive the deterministic Idempotency-Key for a purchase sync
     * request. Returns `null` when no rail-stable identifier is
     * available — caller MUST choose between sending no idempotency
     * header at all OR raising a typed error, never silently mint
     * a random key (defeats the contract).
     */
    fun deriveForPurchase(
        rail: String,
        signedTransactionInfo: String? = null,
        purchaseToken: String? = null,
    ): String? {
        val identifier = when (rail) {
            "apple" -> signedTransactionInfo ?: ""
            "google" -> purchaseToken ?: ""
            else -> ""
        }
        if (identifier.isEmpty()) return null
        val namespaced = "crossdeck:purchases/sync:$rail:$identifier"
        return formatAsUuid(sha256Hex(namespaced))
    }
}
