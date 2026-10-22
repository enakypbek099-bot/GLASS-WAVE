package kz.glasswave.player

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.ArrayDeque

object GwRuntimeLog {
    private const val TAG = "GW_RUNTIME"
    private const val MAX_LINES = 1000

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    private var logFile: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            val directory =
                context.getExternalFilesDir(null) ?: context.filesDir

            directory.mkdirs()

            logFile = File(directory, "glass-wave-runtime.log")
            logFile?.writeText("", Charsets.UTF_8)
            lines.clear()
        }

        info(
            "SESSION",
            "GLASS WAVE runtime started; file=${filePath()}"
        )
    }

    fun info(tag: String, message: String) {
        write("I", tag, message, null)
    }

    fun warn(tag: String, message: String) {
        write("W", tag, message, null)
    }

    fun error(
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        write("E", tag, message, throwable)
    }

    private fun write(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        val suffix = throwable?.let {
            "\n${Log.getStackTraceString(it)}"
        } ?: ""

        val line =
            "${Instant.now()} $level/$tag $message$suffix"

        when (level) {
            "E" -> Log.e(TAG, line)
            "W" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }

        synchronized(lock) {
            lines.addLast(line)

            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }

            runCatching {
                logFile?.appendText("$line\n", Charsets.UTF_8)
            }
        }
    }

    fun snapshot(): String = synchronized(lock) {
        buildString {
            appendLine("GLASS WAVE · GW Native Runtime Diagnostics")
            appendLine("File: ${filePath()}")
            appendLine()
            lines.forEach { appendLine(it) }
        }
    }

    fun filePath(): String = synchronized(lock) {
        logFile?.absolutePath ?: "NOT_INITIALIZED"
    }
}
