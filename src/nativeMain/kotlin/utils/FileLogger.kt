package utils

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.*
import kotlin.concurrent.AtomicInt

@OptIn(ExperimentalForeignApi::class)
private fun closeFileHandler() {
    LoggerManager.closeFile()
}

enum class LogOutputMode {
    FILE_ONLY,
    TERMINAL_ONLY,
    BOTH
}

@OptIn(ExperimentalForeignApi::class)
object LoggerManager {
    private val mutex = AtomicInt(0)
    internal var file: CPointer<FILE>? = null
    internal var initialized = false
    internal var outputMode: LogOutputMode = LogOutputMode.FILE_ONLY

    fun init(logDir: String?, fileName: String, outputMode: LogOutputMode) {
        if (initialized) return

        this.outputMode = outputMode

        if (outputMode == LogOutputMode.TERMINAL_ONLY) {
            initialized = true
            return
        }

        val fullPath = if (!logDir.isNullOrEmpty()) {
            if (mkdir(logDir, (S_IRWXU or S_IRWXG or S_IRWXO).toUInt()) != 0 && errno != EEXIST) {
                perror("Failed to create log directory $logDir")
                error("Failed to create log directory $logDir")
            }
            "$logDir/$fileName"
        } else {
            fileName
        }

        file = fopen(fullPath, "w") ?: run {
            perror("Failed to open log file $fullPath")
            error("Failed to open log file $fullPath")
        }

        initialized = true
        atexit(staticCFunction(::closeFileHandler))
    }

    internal fun closeFile() {
        file?.let { fclose(it) }
    }

    fun write(level: String, message: String, tag: String) {
        if (!initialized) error("LoggerManager not initialized. Call LoggerManager.init(...) before use.")

        val timestamp = TimeUtils.getCurrentTimestamp()
        val logLine = "$tag @ $timestamp [$level]: $message\n"

        try {
            when (outputMode) {
                LogOutputMode.FILE_ONLY -> {
                    while (!mutex.compareAndSet(0, 1)) {
                        // busy-wait :/
                    }

                    fputs(logLine, file)
                    fflush(file)
                }

                LogOutputMode.TERMINAL_ONLY -> {
                    fputs(logLine, stdout)
                    fflush(stdout)
                }

                LogOutputMode.BOTH -> {
                    while (!mutex.compareAndSet(0, 1)) {
                        // busy-wait :/
                    }

                    fputs(logLine, file)
                    fflush(file)
                    fputs(logLine, stdout)
                    fflush(stdout)
                }
            }
        } finally {
            mutex.value = 0
        }
    }
}

class Logger(val tag: String) {
    fun info(message: String) = LoggerManager.write("INFO", message, tag)
    fun warn(message: String) = LoggerManager.write("WARN", message, tag)
    fun debug(message: String) = LoggerManager.write("DEBUG", message, tag)
    fun error(message: String) = LoggerManager.write("ERROR", message, tag)

    inline fun info(message: () -> String) = LoggerManager.write("INFO", message(), tag)
    inline fun warn(message: () -> String) = LoggerManager.write("WARN", message(), tag)
    inline fun debug(message: () -> String) = LoggerManager.write("DEBUG", message(), tag)
    inline fun error(message: () -> String) = LoggerManager.write("ERROR", message(), tag)
}