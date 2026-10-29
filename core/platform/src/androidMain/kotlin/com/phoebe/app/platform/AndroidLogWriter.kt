package com.phoebe.app.platform

import android.os.Looper
import android.os.Process
import android.util.Log
import co.touchlab.kermit.Severity

/**
 * Logcat writer. Logcat already timestamps lines, so only the severity and thread id are added,
 * and messages past Logcat's ~4000 char line limit are split.
 */
internal class AndroidLogWriter : AppLogWriter() {
    override fun threadId(): String =
        if (Looper.myLooper() == Looper.getMainLooper()) "UI" else "%02X".format(Process.myTid() % 256)

    override fun logToConsole(time: String, severity: Severity, threadId: String, tag: String, message: String) {
        val level = when (severity) {
            Severity.Verbose -> Log.VERBOSE
            Severity.Debug -> Log.DEBUG
            Severity.Info -> Log.INFO
            Severity.Warn -> Log.WARN
            Severity.Error -> Log.ERROR
            Severity.Assert -> Log.ASSERT
        }
        val line = "$threadId $message"
        if (line.length <= MaxLogLength) {
            Log.println(level, tag, line)
            return
        }
        var index = 0
        while (index < line.length) {
            val end = minOf(index + MaxLogLength, line.length)
            // prefer to break on a space within the chunk
            val breakAt = if (end < line.length) {
                val space = line.lastIndexOf(' ', end - 1)
                if (space > index) space + 1 else end
            } else {
                end
            }
            Log.println(level, tag, line.substring(index, breakAt))
            index = breakAt
        }
    }

    private companion object {
        const val MaxLogLength = 4000
    }
}
