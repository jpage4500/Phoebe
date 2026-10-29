package com.phoebe.app.platform

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Logs every request made by a client that calls [installHttpLog], under the [Tag] tag.
 *
 * Each request gets a number `(N)` so its response can be matched up. Request lines and response
 * status/timing are logged at debug level; failures and error statuses as warnings. Request bodies
 * are never logged (sign-in bodies carry passwords). Error response bodies are logged when they are
 * small and textual, since that is where servers explain what went wrong.
 */
@OptIn(ExperimentalAtomicApi::class)
object HttpLog {
    const val Tag = "HttpLog"

    private val log = PhoebeLog.logger(Tag)

    private val reqNumKey = AttributeKey<Int>("HttpLogReqNum")
    private val counter = AtomicInt(0)

    // error bodies longer than this are cut short in the log
    private const val MaxBodyChars = 2_000

    // only error bodies at or under this size are read for logging
    private const val MaxBodyBytes = 20_000L

    // query-param names whose values are redacted (case-insensitive). covers Jellyfin/Emby api keys
    // and Quick Connect secrets, Plex tokens, and Subsonic's password/token/salt params.
    private val sensitiveParams = setOf(
        "api_key", "apikey", "access_token", "token", "x-plex-token", "secret", "password", "p", "t", "s",
    )

    private val sensitiveParamRegex =
        Regex("([?&](?:${sensitiveParams.joinToString("|")})=)[^&\\s,\\]\"'<>]+", RegexOption.IGNORE_CASE)

    // Token="..." inside a MediaBrowser Authorization header, should one ever end up in a message
    private val authTokenRegex = Regex("(Token=\")[^\"]*(\")")

    internal fun logRequest(request: HttpRequestBuilder) {
        val n = counter.incrementAndFetch()
        request.attributes.put(reqNumKey, n)
        log.d { "($n) ${request.method.value} ${sanitizeUrl(request.url.buildString())}" }
    }

    internal fun logResponse(response: HttpResponse) {
        val tag = tag(response.call.attributes.getOrNull(reqNumKey))
        val ms = response.responseTime.timestamp - response.requestTime.timestamp
        val size = response.contentLength()?.let { " ${formatBytes(it)}" }.orEmpty()
        if (response.status.isSuccess()) {
            log.d { "DONE: $tag ${response.status.value} ${ms}ms$size" }
        } else {
            log.w("$tag HTTP_ERROR ${response.status.value} ${ms}ms ${sanitizeUrl(response.call.request.url.toString())}")
        }
    }

    internal fun logFailure(request: HttpRequestBuilder, cause: Throwable) {
        log.w("!! ${tag(request.attributes.getOrNull(reqNumKey))} FAILED ${sanitizeUrl(request.url.buildString())}: ${cause.logDetail()}")
    }

    internal fun shouldLogErrorBody(response: HttpResponse): Boolean {
        if (response.status.isSuccess()) return false
        if (!isTextual(response.contentType())) return false
        val len = response.contentLength() ?: return true
        return len in 1..MaxBodyBytes
    }

    internal suspend fun logErrorBody(response: HttpResponse) {
        val tag = tag(response.call.attributes.getOrNull(reqNumKey))
        val body = runCatching { readLoggablePrefix(response) }.getOrNull()?.trim()
        if (body.isNullOrEmpty()) return
        val shown = if (body.length <= MaxBodyChars) body else body.take(MaxBodyChars) + "…"
        log.w("<< $tag ${redact(shown).replace("\n", "⏎ ")}")
    }

    // keep the first MaxBodyBytes but drain the rest: the observer reads a split copy of the body,
    // and leaving bytes unread would stall the real consumer
    private suspend fun readLoggablePrefix(response: HttpResponse): String {
        val channel = response.bodyAsChannel()
        val head = ByteArray(MaxBodyBytes.toInt())
        val buf = ByteArray(8 * 1024)
        var kept = 0
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buf, 0, buf.size)
            if (read <= 0) break
            if (kept < head.size) {
                val take = minOf(read, head.size - kept)
                buf.copyInto(head, kept, 0, take)
                kept += take
            }
        }
        return head.decodeToString(0, kept)
    }

    /** Redact secrets (sensitive query params, Authorization tokens) from arbitrary text. */
    fun redact(text: String): String {
        if ('=' !in text) return text
        val noParams = sensitiveParamRegex.replace(text) { it.groupValues[1] + "***" }
        return authTokenRegex.replace(noParams) { it.groupValues[1] + "***" + it.groupValues[2] }
    }

    fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx == -1) return url
        val redacted = url.substring(qIdx + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq == -1 || pair.substring(0, eq).lowercase() !in sensitiveParams) pair else pair.substring(0, eq) + "=***"
        }
        return url.substring(0, qIdx + 1) + redacted
    }

    private fun tag(n: Int?): String = if (n != null) "($n)" else "(?)"

    private fun isTextual(ct: ContentType?): Boolean {
        if (ct == null) return true
        return ct.match(ContentType.Text.Any) ||
            ct.match(ContentType.Application.Json) ||
            ct.match(ContentType.Application.Xml) ||
            ct.match(ContentType.Application.ProblemJson)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }
}

/**
 * Install [HttpLog] into a client config: request lines, response status/timing and failures,
 * plus small textual error bodies via [ResponseObserver] (which reads a split copy, so the caller's
 * body is untouched).
 */
fun HttpClientConfig<*>.installHttpLog() {
    install(httpLogPlugin)
    install(ResponseObserver) {
        filter { call -> HttpLog.shouldLogErrorBody(call.response) }
        onResponse { response -> HttpLog.logErrorBody(response) }
    }
}

private val httpLogPlugin = createClientPlugin("HttpLog") {
    onRequest { request, _ ->
        HttpLog.logRequest(request)
    }
    on(Send) { request ->
        try {
            val call = proceed(request)
            HttpLog.logResponse(call.response)
            call
        } catch (cause: Throwable) {
            HttpLog.logFailure(request, cause)
            throw cause
        }
    }
}
