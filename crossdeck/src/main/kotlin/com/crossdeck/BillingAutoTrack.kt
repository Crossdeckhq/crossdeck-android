package com.crossdeck

/**
 * Google Play Billing helpers — Android equivalent of the Swift
 * SDK's `Transaction.updates` StoreKit 2 listener.
 *
 * Why these are HELPERS, not auto-listeners: Google Play Billing's
 * BillingClient is owned by the consumer's code (lifecycle-bound to
 * an Activity / Service), and the BillingClient instance can't be
 * shared across modules. So Crossdeck provides the wiring helpers
 * the consumer calls from inside their own PurchasesUpdatedListener.
 *
 * Consumer pattern:
 *
 * ```kotlin
 * val billingClient = BillingClient.newBuilder(context)
 *     .setListener(PurchasesUpdatedListener { result, purchases ->
 *         crossdeck.handleBillingResult(result, purchases)
 *     })
 *     .enablePendingPurchases()
 *     .build()
 * ```
 *
 * `handleBillingResult` does two things:
 *   1. Forwards every purchase's signed payload to `/purchases/sync`
 *      via the same backend contract the manual `syncPurchases()`
 *      uses.
 *   2. Fires a `purchase.completed` / `purchase.refunded` event
 *      through the normal track() pipeline so dashboards see the
 *      funnel boundary.
 *
 * No reflection, no dependency on com.android.billingclient — we
 * accept generic shapes (responseCode, purchasesList) so this
 * helper compiles regardless of which BillingClient version the
 * consumer has. The consumer's own code keeps the strong types.
 */
public data class BillingPurchase(
    /** The product ID (SKU) the user purchased. */
    val productId: String,
    /** The order ID assigned by Google Play (server-side unique). */
    val orderId: String?,
    /** The purchase token — the canonical billing-side identity. */
    val purchaseToken: String,
    /** Purchase time in epoch milliseconds. */
    val purchaseTimeMillis: Long,
    /**
     * The signature Google Play attaches to the purchase JSON so
     * the backend can verify the receipt cryptographically.
     */
    val signature: String?,
    /**
     * Whether the purchase has been acknowledged (pending → acked).
     * Affects entitlement projection semantics.
     */
    val isAcknowledged: Boolean = true,
    /**
     * Whether the purchase was revoked / refunded. Some apps surface
     * this in their own purchase-history fetches and want to report
     * it; auto-detection on top of BillingClient.queryPurchaseHistoryAsync
     * is brittle, so we let the consumer flag explicitly.
     */
    val isRevoked: Boolean = false,
)

/**
 * Forward a Google Play Billing result into Crossdeck — handles
 * both the analytics emission and the backend sync.
 *
 * The backend sync runs on the SDK's internal scope. Failures DO
 * NOT silently swallow — they surface as a `purchase.sync_failed`
 * analytics event (visible in dashboards) AND via the optional
 * [onSyncResult] callback for programmatic handling (retry,
 * persist-for-later, etc.).
 *
 * @param responseCode the BillingResult.responseCode (0 = OK).
 * @param purchases the list of [BillingPurchase] entries to report;
 *   builders convert from `com.android.billingclient.api.Purchase`.
 * @param onSyncResult optional callback fired once per signed
 *   purchase after the `/purchases/sync` round-trip completes.
 *   Default: no-op. Receives a typed [Result] — `Result.failure`
 *   carries a [CrossdeckError] with `type`, `code`, and
 *   `statusCode` set.
 */
public fun Crossdeck.handleBillingResult(
    responseCode: Int,
    purchases: List<BillingPurchase>?,
    onSyncResult: (BillingPurchase, Result<Unit>) -> Unit = { _, _ -> },
) {
    // OK code in BillingClient is 0. Anything else is a user-
    // cancelled / network / config failure that should NOT enqueue
    // a purchase event but COULD enqueue a `purchase.failed` event
    // for the funnel.
    if (responseCode != 0) {
        track(
            "purchase.failed",
            mapOf("billingResponseCode" to responseCode),
        )
        return
    }
    if (purchases.isNullOrEmpty()) return

    for (purchase in purchases) {
        // Fire the public funnel event first — the dashboard
        // row appears immediately even if the backend sync is in
        // flight.
        val name = if (purchase.isRevoked) "purchase.refunded" else "purchase.completed"
        val props = mutableMapOf<String, Any?>(
            "productId" to purchase.productId,
            "purchaseToken" to purchase.purchaseToken,
            "purchaseTimeMillis" to purchase.purchaseTimeMillis,
            "isAcknowledged" to purchase.isAcknowledged,
        )
        purchase.orderId?.let { props["orderId"] = it }
        track(name, props)

        // Hand the signed payload to the backend for verification.
        // Failures propagate via the onSyncResult callback AND emit
        // a `purchase.sync_failed` analytics event (defense-in-depth
        // per founder principle 2).
        if (!purchase.signature.isNullOrEmpty()) {
            syncBillingPurchaseAsync(purchase) { result ->
                onSyncResult(purchase, result)
            }
        }
    }
}

/**
 * Pure mapper from an [HttpSendOutcome] into a typed [Result] for
 * the billing-sync path. Lifted out of [Crossdeck.syncBillingPurchase]
 * so it's unit-testable without an Android Application context.
 *
 * Bank-grade contract: every non-SUCCESS outcome produces a
 * [Result.failure] carrying a [CrossdeckError] with a populated
 * `type` and `code`, even if the underlying [HttpSendOutcome.error]
 * was null (defensive). Callers can match on `code` to
 * distinguish synthetic failures from real backend envelopes.
 */
internal fun mapBillingSyncOutcome(outcome: HttpSendOutcome): Result<Unit> {
    if (outcome.kind == HttpSendOutcome.Kind.SUCCESS) {
        return Result.success(Unit)
    }
    val existing = outcome.error
    if (existing != null) {
        return Result.failure(existing)
    }
    return Result.failure(
        CrossdeckError(
            type = CrossdeckErrorType.INTERNAL_ERROR,
            code = "auto_billing_sync_failed",
            message = "/purchases/sync did not succeed.",
            statusCode = outcome.envelope?.statusCode,
        ),
    )
}
