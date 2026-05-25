// Server-endpoint wire shapes — mirror sdks/swift/Sources/Crossdeck/
// ServerEndpoints.swift and sdks/react-native/src/types.ts byte-for-
// byte. The backend route validators in backend/src/api/v1-*.ts are
// the source of truth; these data classes round-trip the same JSON.
//
// Field-naming + endpoint-path notes:
//
//   * POST /identity/alias body — { userId, anonymousId, email?, traits? }
//     environment + appId are derived from the API key server-side,
//     NOT sent in the body. Confirmed against
//     backend/src/api/v1-alias.ts:133.
//
//   * POST /identity/forget body — { userId?, anonymousId?, customerId? }
//     backend reads body.customerId, NOT body.crossdeckCustomerId.
//     Confirmed against backend/src/api/v1-forget.ts:131.
//
//   * GET /entitlements query — userId / customerId / anonymousId
//     at least one is required (backend/src/api/v1-entitlements.ts:92).
//
//   * POST /purchases/sync body — { rail, signedTransactionInfo?,
//     signedRenewalInfo?, purchaseToken?, appAccountToken? }
//     v1.0.0 supports rail=apple AND rail=google (matches the wire
//     contract); backend currently rejects rail=google with
//     `google_not_supported` (v1.1 wires Google Play). Android
//     consumers calling syncPurchases(rail=GOOGLE, ...) will see
//     that 4xx until backend lights up.
//
//   * GET /sdk/heartbeat — no body. Backend reads appId + env from
//     the API key + the Crossdeck-Sdk-Version header.

package com.crossdeck

public enum class AuditRail(public val wireValue: String) {
    STRIPE("stripe"),
    APPLE("apple"),
    GOOGLE("google"),
    MANUAL("manual"),
    ;

    public companion object {
        public fun fromWire(value: String?): AuditRail? =
            values().firstOrNull { it.wireValue == value }
    }
}

/** Single entitlement from /entitlements or /purchases/sync. */
public data class PublicEntitlement(
    val key: String,
    val isActive: Boolean,
    /** Epoch ms — when set, `isEntitled` honours it as the expiry. */
    val validUntil: Long? = null,
    val source: EntitlementSource,
    val updatedAt: Long,
) {
    public data class EntitlementSource(
        val rail: AuditRail,
        val productId: String,
        val subscriptionId: String,
    )
}

public data class AliasResult(
    val crossdeckCustomerId: String,
    val mergePending: Boolean,
    val env: Environment,
    val linked: List<LinkedIdentity>,
) {
    public data class LinkedIdentity(
        /** "developer" or "anonymous". */
        val type: String,
        val id: String,
    )
}

public data class EntitlementsListResponse(
    val data: List<PublicEntitlement>,
    val crossdeckCustomerId: String,
    val env: Environment,
)

public data class PurchaseResult(
    val crossdeckCustomerId: String,
    val env: Environment,
    val entitlements: List<PublicEntitlement>,
)

public data class HeartbeatResponse(
    val ok: Boolean,
    val projectId: String,
    val appId: String,
    val env: Environment,
    val serverTime: Long,
)

// ---------------------------------------------------------------
// Request bodies
//
// These shapes mirror the backend route validators exactly.
// Backend file paths are noted on each — drift here is an ingest
// rejection, not a 4xx the caller would otherwise see.
// ---------------------------------------------------------------

/**
 * POST /identity/alias body. Validated by
 * backend/src/api/v1-alias.ts:133.
 *
 * `environment` + `appId` are derived from the API key server-
 * side — DO NOT add them here, the validator rejects unknown
 * top-level fields.
 */
public data class AliasIdentityRequest(
    val userId: String,
    val anonymousId: String,
    val email: String? = null,
    /** String-keyed string-valued bag; complex traits get coerced upstream. */
    val traits: Map<String, String>? = null,
)

/**
 * POST /identity/forget body. Validated by
 * backend/src/api/v1-forget.ts:131. Backend reads `customerId`
 * (NOT `crossdeckCustomerId`); at least one of the three hints
 * is required.
 */
public data class ForgetIdentityRequest(
    val userId: String? = null,
    val anonymousId: String? = null,
    val customerId: String? = null,
)

/**
 * POST /purchases/sync body. Validated by
 * backend/src/api/v1-purchases-validation.ts:27.
 *
 * Apple StoreKit 2 fields ride on `signedTransactionInfo` +
 * `signedRenewalInfo`. Google Play (v1.1) rides on
 * `purchaseToken`. Backend will reject mismatches.
 */
public data class PurchaseSyncRequest(
    val rail: String,
    val signedTransactionInfo: String? = null,
    val signedRenewalInfo: String? = null,
    val purchaseToken: String? = null,
    val appAccountToken: String? = null,
)
