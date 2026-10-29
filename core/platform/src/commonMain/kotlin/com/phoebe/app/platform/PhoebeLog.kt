package com.phoebe.app.platform

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.LoggerConfig
import co.touchlab.kermit.Severity
import com.phoebe.app.telemetry.Telemetry

/** True for local/dev builds; false for store/release artifacts. */
expect fun isDebugBuild(): Boolean

/**
 * Logging facade over Kermit. Local console output (Logcat, OSLog, stdout) is debug-only;
 * telemetry still receives logs when configured.
 * Prefer [v] for high-volume traces and [d] for lifecycle / error detail.
 */
object PhoebeLog {
    // one config shared by every logger, so the writer list is decided in a single place
    private val config = object : LoggerConfig {
        override val logWriterList: List<LogWriter> by lazy {
            listOfNotNull(
                createPlatformLogWriter().takeIf { isDebugBuild() },
                TelemetryLogWriter,
            )
        }
        override val minSeverity: Severity = Severity.Verbose
    }

    private val default = Logger(config, "Phoebe")

    /** Kermit logger for a class that logs a lot under one tag: `private val log = PhoebeLog.logger("Tag")`. */
    fun logger(tag: String): Logger = Logger(config, tag)

    fun v(tag: String, message: String) = default.v(message, tag = tag)

    fun v(tag: String, lazyMessage: () -> String) = default.v(tag = tag, message = lazyMessage)

    fun d(tag: String, message: String) = default.d(message, tag = tag)

    fun d(tag: String, lazyMessage: () -> String) = default.d(tag = tag, message = lazyMessage)

    fun i(tag: String, message: String) = default.i(message, tag = tag)

    fun w(tag: String, message: String, throwable: Throwable? = null) = default.w(message, throwable, tag)

    fun e(tag: String, message: String, throwable: Throwable? = null) = default.e(message, throwable, tag)
}

/**
 * Forwards logs to [Telemetry]. HTTP request traces stay local unless they are warnings or worse,
 * so routine request URLs never leave the device.
 */
private object TelemetryLogWriter : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean =
        tag != HttpLog.Tag || severity >= Severity.Warn

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val detail = throwable?.let { " (${it.logDetail()})" }.orEmpty()
        Telemetry.log(tag, HttpLog.redact(message) + detail)
    }
}

/**
 * Compact class + message chain for probe/player diagnostics.
 * Strips query strings from http(s) URLs so tokens do not land in Sentry.
 */
fun Throwable.logDetail(maxDepth: Int = 3): String = buildString {
    var current: Throwable? = this@logDetail
    var depth = 0
    while (current != null && depth < maxDepth) {
        if (depth > 0) append(" | ")
        append(current::class.simpleName ?: "Throwable")
        current.message?.takeIf { it.isNotBlank() }?.let { message ->
            append(": ").append(redactUrlQueryParams(message).take(240))
        }
        current = current.cause
        depth++
    }
}

private fun redactUrlQueryParams(text: String): String =
    text.replace(Regex("""(https?://[^\s"'<>?]+)(\?[^\s"'<>]*)""", RegexOption.IGNORE_CASE)) { match ->
        match.groupValues[1]
    }

/** Console writer for this platform; only used in debug builds. */
internal expect fun createPlatformLogWriter(): LogWriter
