package com.phoebe.app.platform

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * Console log writer with a compact, consistent format:
 * `time [L] TT Tag: message`
 * where L is the severity letter and TT a short thread id ("UI" for the main thread).
 * Platforms override the pieces they can provide (thread id, timestamp, console call).
 */
open class AppLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val time = timestamp()
        val threadId = threadId()
        logToConsole(time, severity, threadId, tag, message)
        if (throwable != null) {
            logToConsole(time, severity, threadId, tag, throwable.stackTraceToString())
        }
    }

    /** Short thread identifier, or empty when the platform can't tell. */
    protected open fun threadId(): String = ""

    /** Time prefix, or empty when the console already timestamps lines (Logcat, OSLog). */
    protected open fun timestamp(): String = ""

    protected open fun logToConsole(time: String, severity: Severity, threadId: String, tag: String, message: String) {
        val prefix = listOf(time, "[${severity.letter()}]", threadId).filter { it.isNotEmpty() }.joinToString(" ")
        println("$prefix $tag: $message")
    }
}

internal fun Severity.letter(): String = when (this) {
    Severity.Verbose -> "V"
    Severity.Debug -> "D"
    Severity.Info -> "I"
    Severity.Warn -> "W"
    Severity.Error -> "E"
    Severity.Assert -> "A"
}
