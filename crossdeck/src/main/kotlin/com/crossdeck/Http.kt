// HttpURLConnection-based HTTP client.
//
// Zero third-party deps. OkHttp is the de-facto Android client
// but every Android shop pins a different OkHttp version — adding
// it as a dep here forces consumers to inherit our pin or risk
// runtime classpath conflicts. HttpURLConnection (on the platform)
// has every primitive we need.
//
// Hard-coded behaviour:
//   * 30s connect/read timeout (anything longer wedges the queue)
//   * `Authorization: Bearer <publicKey>`
//   * `Crossdeck-Sdk-Version: <SDK.NAME>@<SDK.VERSION>` — backend
//     v1-heartbeat.ts:140 parses this to populate the per-SDK
//     dashboard tile. Missing this on the wire would leave the
//     "Android SDK" install row dark even when events land.
//   * `Idempotency-Key` for any retryable POST (caller sets)
//   * `User-Agent` includes SDK name + version

package com.crossdeck

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

public data class HttpResponseEnvelope(
    val statusCode: Int,
    val body: String?,
    val retryAfterMs: Long?,
    val requestId: String?,
)

public class HttpSendOutcome(
    public val kind: Kind,
    public val envelope: HttpResponseEnvelope?,
    public val error: CrossdeckError?,
) {
    public enum class Kind { SUCCESS, RETRYABLE, PERMANENT }
}

public class HttpClient(
    /** API base URL — e.g. https://api.cross-deck.com/v1 */
    public val baseUrl: String,
    public val publicKey: String,
    /**
     * Android package name (`context.packageName` / `applicationId`).
     * Sent as `X-Crossdeck-Package-Name` on every request so the
     * backend's `isPackageNameAllowed()` can enforce the identity
     * lock per app key. Bank-grade fail-closed: a request with a
     * mismatched package name is rejected with 403 /
     * package_name_not_allowed. Pass empty string if the consumer
     * really needs to suppress the header (test harnesses) — the
     * backend will reject those requests too, which is correct.
     */
    public val packageName: String,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val userAgent = "${Sdk.NAME}/${Sdk.VERSION}"
    private val sdkVersionHeader = "${Sdk.NAME}@${Sdk.VERSION}"

    /**
     * Generic request. Used by every endpoint (heartbeat, alias,
     * forget, entitlements, purchases/sync, events). `path` is
     * appended to `baseUrl`; `query` is URL-encoded.
     */
    public fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        query: Map<String, String>? = null,
        idempotencyKey: String? = null,
    ): HttpSendOutcome {
        var connection: HttpURLConnection? = null
        try {
            val trimmedPath = if (path.startsWith("/")) path.drop(1) else path
            val withSlash = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            var urlString = "$withSlash$trimmedPath"
            if (!query.isNullOrEmpty()) {
                val qs = query.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                }
                urlString = "$urlString?$qs"
            }
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $publicKey")
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Crossdeck-Sdk-Version", sdkVersionHeader)
                if (packageName.isNotEmpty()) {
                    setRequestProperty("X-Crossdeck-Package-Name", packageName)
                }
                if (idempotencyKey != null) {
                    setRequestProperty("Idempotency-Key", idempotencyKey)
                }
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            return classifyResponse(connection)
        } catch (e: IOException) {
            return HttpSendOutcome(
                HttpSendOutcome.Kind.RETRYABLE,
                null,
                CrossdeckError(
                    type = CrossdeckErrorType.NETWORK,
                    code = "io_error",
                    message = e.message ?: "Network IO error.",
                    cause = e,
                ),
            )
        } catch (e: Exception) {
            return HttpSendOutcome(
                HttpSendOutcome.Kind.RETRYABLE,
                null,
                CrossdeckError(
                    type = CrossdeckErrorType.NETWORK,
                    code = "transport_error",
                    message = e.message ?: "Transport error.",
                    cause = e,
                ),
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Convenience for the events POST — preserves the same shape
     * the queue used pre-generic. Body is required; idempotencyKey
     * is required (each batch has its own stable key for server-
     * side dedup across retries).
     */
    public fun sendEvents(body: ByteArray, idempotencyKey: String): HttpSendOutcome =
        request("POST", "/events", body = body, idempotencyKey = idempotencyKey)

    private fun classifyResponse(connection: HttpURLConnection): HttpSendOutcome {
        val status = connection.responseCode
        val responseBody = readResponseBody(connection, status)
        val retryAfterMs = parseRetryAfterHeader(connection.getHeaderField("Retry-After"))
        val requestId = connection.getHeaderField("Request-Id")
            ?: connection.getHeaderField("X-Request-Id")

        val envelope = HttpResponseEnvelope(
            statusCode = status,
            body = responseBody,
            retryAfterMs = retryAfterMs,
            requestId = requestId,
        )

        return when {
            status in 200..299 -> HttpSendOutcome(HttpSendOutcome.Kind.SUCCESS, envelope, null)
            // 4xx is permanent EXCEPT 408 (Request Timeout) and
            // 429 (Rate Limit). 408 means the upstream proxy gave
            // up waiting for our request to land and is happy for
            // us to retry; 429 means we hit the rate limit and
            // must honour Retry-After. Dropping either as
            // permanent would silently lose batches under transient
            // load — exact carve-out matches Web/Node/RN/Swift.
            status in 400..499 && status != 408 && status != 429 -> {
                val err = crossdeckErrorFromResponse(status, requestId, responseBody)
                HttpSendOutcome(HttpSendOutcome.Kind.PERMANENT, envelope, err)
            }
            else -> {
                val err = crossdeckErrorFromResponse(status, requestId, responseBody)
                HttpSendOutcome(HttpSendOutcome.Kind.RETRYABLE, envelope, err)
            }
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, status: Int): String? {
        val stream = try {
            if (status >= 400) conn.errorStream else conn.inputStream
        } catch (_: Exception) {
            return null
        } ?: return null
        return try {
            stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
        } catch (_: Exception) {
            null
        }
    }
}

// MARK: - Self-request detection

/**
 * Extract a hostname from a URL string. Returns null on
 * malformed input. Lowercased for case-insensitive compares.
 */
public fun extractSelfHostname(urlString: String): String? {
    return try {
        URL(urlString).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}

/**
 * True iff the given URL targets the SDK's own ingest hostname.
 * Used by error capture to skip its own outgoing requests —
 * without this, a failed ingest would generate an error event
 * which would itself fail, ad infinitum.
 */
public fun isSelfRequest(urlString: String, selfHostname: String?): Boolean {
    if (selfHostname.isNullOrEmpty()) return false
    val candidate = extractSelfHostname(urlString) ?: return false
    return candidate.equals(selfHostname, ignoreCase = true)
}
