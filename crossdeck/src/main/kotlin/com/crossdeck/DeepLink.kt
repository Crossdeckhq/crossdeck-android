package com.crossdeck

import android.content.Intent
import android.net.Uri

/**
 * Deep-link + push interaction tracking helpers.
 *
 * Same shape as the Swift SDK's [Crossdeck.trackDeepLink] /
 * [Crossdeck.trackPushInteraction] surface. Consumers forward
 * from their Activity.onNewIntent / FirebaseMessagingService
 * (or equivalent push receiver):
 *
 * ```kotlin
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     intent.data?.let { crossdeck.trackDeepLink(it) }
 * }
 *
 * override fun onMessageReceived(message: RemoteMessage) {
 *     crossdeck.trackPushReceived(message.data)
 * }
 * ```
 *
 * These helpers do NOT auto-capture because Android's intent /
 * push delivery paths are owned by the consumer's components
 * (Activity / Service / BroadcastReceiver) — no global hook is
 * safe to install on top of those.
 */
public fun Crossdeck.trackDeepLink(uri: Uri, source: String? = null) {
    val props = mutableMapOf<String, Any?>("url" to uri.toString())
    uri.host?.takeIf { it.isNotEmpty() }?.let { props["host"] = it }
    uri.path?.takeIf { it.isNotEmpty() }?.let { props["path"] = it }
    source?.let { props["source"] = it }

    // Surface UTM + click-id parameters per Web SDK convention.
    val attributionKeys = setOf(
        "utm_source", "utm_medium", "utm_campaign",
        "utm_content", "utm_term",
        "gclid", "fbclid", "msclkid",
        "ttclid", "li_fat_id", "twclid",
    )
    try {
        for (name in uri.queryParameterNames) {
            val lower = name.lowercase()
            if (lower in attributionKeys) {
                val value = uri.getQueryParameter(name)
                if (!value.isNullOrEmpty()) {
                    props[lower] = value
                }
            }
        }
    } catch (_: UnsupportedOperationException) {
        // Some URIs (mailto:, custom schemes) don't support
        // query-parameter iteration. Skip gracefully.
    }
    track("deeplink.opened", props)
}

/**
 * Track a deep link from an [Intent] — extracts the data URI plus
 * the intent's action / package. Use when the consumer wants the
 * intent fully surfaced rather than just its URI.
 */
public fun Crossdeck.trackDeepLinkIntent(intent: Intent, source: String? = null) {
    val uri = intent.data ?: return
    val props = mutableMapOf<String, Any?>("url" to uri.toString())
    intent.action?.takeIf { it.isNotEmpty() }?.let { props["action"] = it }
    intent.`package`?.takeIf { it.isNotEmpty() }?.let { props["package"] = it }
    uri.host?.takeIf { it.isNotEmpty() }?.let { props["host"] = it }
    uri.path?.takeIf { it.isNotEmpty() }?.let { props["path"] = it }
    source?.let { props["source"] = it }

    val attributionKeys = setOf(
        "utm_source", "utm_medium", "utm_campaign",
        "utm_content", "utm_term",
        "gclid", "fbclid", "msclkid",
        "ttclid", "li_fat_id", "twclid",
    )
    try {
        for (name in uri.queryParameterNames) {
            val lower = name.lowercase()
            if (lower in attributionKeys) {
                val value = uri.getQueryParameter(name)
                if (!value.isNullOrEmpty()) {
                    props[lower] = value
                }
            }
        }
    } catch (_: UnsupportedOperationException) {
    }
    track("deeplink.opened", props)
}

/**
 * Track a push notification received while the app is in the
 * foreground. Wire this from `FirebaseMessagingService.onMessageReceived`
 * or equivalent push handler.
 *
 * PII protection: never logs the alert body / title — only
 * structural marketing-platform IDs are promoted to top-level
 * properties.
 */
public fun Crossdeck.trackPushReceived(data: Map<String, String>) {
    val props = mutableMapOf<String, Any?>()
    val surfacedKeys = listOf(
        "campaign_id", "campaignId",
        "message_id", "messageId",
        "notification_id", "notificationId",
        "track_id", "trackId",
        "type", "category",
    )
    for (key in surfacedKeys) {
        val value = data[key]
        if (!value.isNullOrEmpty()) {
            props[key] = value
        }
    }
    track("push.received", props)
}

/**
 * Track a push notification interaction (the user tapped the
 * notification, or a notification action). Wire from your
 * Activity's `onCreate` / `onNewIntent` when the launching intent
 * carries notification data.
 */
public fun Crossdeck.trackPushInteraction(
    data: Map<String, String>,
    actionId: String? = null,
) {
    val props = mutableMapOf<String, Any?>()
    actionId?.let { props["actionId"] = it }
    val surfacedKeys = listOf(
        "campaign_id", "campaignId",
        "message_id", "messageId",
        "notification_id", "notificationId",
        "track_id", "trackId",
        "type", "category",
    )
    for (key in surfacedKeys) {
        val value = data[key]
        if (!value.isNullOrEmpty()) {
            props[key] = value
        }
    }
    track("push.interacted", props)
}
