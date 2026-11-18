package us.adfoc.gradle

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.math.min

/** A failure, with AdFoc.us's own error code (bad_url, missing_scope, rate_limited, ...) and HTTP status (0: no answer). */
class AdfocusException(val status: Int, val code: String, message: String) : RuntimeException(message)

/** The link AdFoc.us made, or the one you had for that address (existed). */
data class MadeLink(val id: Long, val shortUrl: String, val destination: String, val existed: Boolean)

/**
 * POST /api/v2/links, as the JavaScript SDK does it: the key in X-API-Key only, https only (except this computer, for tests), no
 * redirects, and up to [maxRetries] more tries on 429 and 5xx (Retry-After honoured up to [maxRetryWait] seconds).
 */
class AdfocusClient(
    private val key: String,
    baseUrl: String,
    private val gate: String? = null,
    private val userAgent: String = "adfocus-gradle/$VERSION",
    private val maxRetries: Int = 3,
    private val maxRetryWait: Long = 60,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val VERSION = "0.1.0"
        const val DEFAULT_BASE_URL = "https://adfoc.us"
        private val KEY_RE = Regex("^afk_[A-Za-z0-9_-]{43}$")
        private val RETRY = setOf(429, 500, 502, 503, 504)

        /** The site's address without /api/v2 or a trailing slash; only https, except this computer. */
        fun siteAddress(raw: String): String {
            val u = try {
                URI(raw.trim())
            } catch (e: Exception) {
                throw AdfocusException(0, "insecure_address", "\"${raw.take(100)}\" is not a web address.")
            }
            val host = u.host ?: throw AdfocusException(0, "insecure_address", "\"${raw.take(100)}\" is not a web address.")
            val local = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
            if (!(u.scheme == "https" || (u.scheme == "http" && local))) throw AdfocusException(0, "insecure_address", "The address must start with https:// so the key is never sent in the open.")
            if (u.userInfo != null) throw AdfocusException(0, "insecure_address", "Give the test site's password as gate (or ADFOCUS_GATE), not in the address.")
            val path = (u.rawPath ?: "").trimEnd('/').removeSuffix("/api/v2")
            return "${u.scheme}://${u.rawAuthority}$path"
        }

        /** Seconds from a Retry-After header: seconds, or a date. */
        fun retryAfterOf(v: String?): Long? {
            val t = v?.trim().orEmpty()
            if (t.isEmpty()) return null
            t.toLongOrNull()?.let { return it }
            return try {
                val at = ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                maxOf(0, at - System.currentTimeMillis() / 1000)
            } catch (e: Exception) {
                null
            }
        }
    }

    private val base = siteAddress(baseUrl)
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()

    fun shorten(url: String): MadeLink {
        if (key.isBlank()) throw AdfocusException(0, "missing_key", "No AdFoc.us key: set ADFOCUS_API_KEY, or adfocus.apiKey in your user gradle.properties. Make one under Tools in your AdFoc.us Dashboard.")
        if (!KEY_RE.matches(key)) throw AdfocusException(0, "bad_key", "That's not an AdFoc.us API key. Keys start with afk_.")
        val req = HttpRequest.newBuilder(URI.create("$base/api/v2/links"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-API-Key", key)
            .apply { if (!gate.isNullOrEmpty()) header("Authorization", "Basic " + Base64.getEncoder().encodeToString(gate.toByteArray())) }
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(mapOf("url" to url))))
            .build()
        var attempt = 0
        while (true) {
            val res: HttpResponse<String> = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                val timedOut = e is HttpTimeoutException
                if (attempt < maxRetries) {
                    sleep(backoff(attempt, null) ?: 0)
                    attempt++
                    continue
                }
                throw AdfocusException(0, if (timedOut) "timeout" else "network_error", if (timedOut) "AdFoc.us didn't answer within 30 seconds." else "Couldn't reach AdFoc.us. Check the connection and try again.")
            }
            val parsed = try {
                Json.parse(res.body())
            } catch (e: IllegalArgumentException) {
                null
            }
            val status = res.statusCode()
            if (status in 200..299 && parsed is Map<*, *>) {
                val link = parsed["link"] as? Map<*, *> ?: throw AdfocusException(status, "not_json", "AdFoc.us answered without a link.")
                return MadeLink((link["id"] as? Number)?.toLong() ?: 0, link["short_url"].toString(), link["destination"].toString(), parsed["existed"] == true)
            }
            val after = retryAfterOf(res.headers().firstValue("Retry-After").orElse(null))
            val apiErr = (parsed as? Map<*, *>)?.get("error") as? Map<*, *>
            val err = when {
                apiErr?.get("code") is String -> AdfocusException(status, apiErr["code"] as String, (apiErr["message"] as? String) ?: apiErr["code"] as String)
                status == 401 && !gate.isNullOrEmpty() -> AdfocusException(status, "bad_gate", "The test site's password is wrong (ADFOCUS_GATE).")
                else -> AdfocusException(status, if (status >= 500) "server_error" else "not_json", "AdFoc.us answered $status without JSON.")
            }
            if (status in RETRY && attempt < maxRetries) {
                val wait = backoff(attempt, after)
                if (wait != null) {
                    sleep(wait)
                    attempt++
                    continue
                }
            }
            throw err
        }
    }

    /** Milliseconds to wait: Retry-After when sent, else 500, 1000, 2000, 4000 (at most 8000) with a little jitter; null
     *  when Retry-After asks for longer than maxRetryWait. */
    private fun backoff(attempt: Int, retryAfter: Long?): Long? {
        if (retryAfter != null) return if (retryAfter > maxRetryWait) null else retryAfter * 1000
        val base = min(8000.0, 500.0 * (1 shl attempt))
        return min(maxRetryWait * 1000, (base * (0.75 + Math.random() * 0.5)).toLong())
    }
}
