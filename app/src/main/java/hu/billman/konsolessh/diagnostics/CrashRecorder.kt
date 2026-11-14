package hu.billman.konsolessh.diagnostics

import java.io.File
import java.io.PrintWriter
import java.time.Instant

/**
 * Writes an uncaught exception's stack trace to a file, then passes the
 * exception on to the previous handler, so Android still stops the app as
 * usual. The system's exit history holds no stack trace for a Java crash,
 * which is why this is needed.
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
