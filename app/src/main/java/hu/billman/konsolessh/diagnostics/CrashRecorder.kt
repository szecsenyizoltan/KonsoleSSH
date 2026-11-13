package hu.billman.konsolessh.diagnostics

import java.io.File
import java.io.PrintWriter
import java.time.Instant

/**
 * Nem kezelt kivételnél a veremet fájlba írja, majd átadja az eredeti
 * kezelőnek (az Android ugyanúgy leállítja az appot). A rendszer kilépési
 * előzménye Java-összeomlásnál nem tartalmaz vermet, ezért kell ez.
 */
object CrashRecorder {

    fun install(file: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(file, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun record(file: File, thread: Thread, error: Throwable) {
        file.parentFile?.mkdirs()
        PrintWriter(file.bufferedWriter()).use { out ->
            out.println("time: ${ReportTime.format(Instant.now())}")
            out.println("thread: ${thread.name}")
            error.printStackTrace(out)
        }
    }
}
