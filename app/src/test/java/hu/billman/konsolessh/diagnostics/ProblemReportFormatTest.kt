package hu.billman.konsolessh.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProblemReportFormatTest {

    @Test
    fun `report has the header and every section`() {
        val text = ProblemReportFormat.compose(
            "KonsoleSSH 1.0",
            listOf(
                ProblemReportFormat.Section("ANR", "main thread blocked"),
                ProblemReportFormat.Section("CRASH", "boom"),
            ),
        )
        assertTrue(text.startsWith("KonsoleSSH 1.0\n"))
        assertTrue(text.contains("== ANR ==\nmain thread blocked"))
        assertTrue(text.contains("== CRASH ==\nboom"))
    }

    @Test
    fun `oversized section is cut with a note`() {
        val text = ProblemReportFormat.compose(
            "h",
            listOf(ProblemReportFormat.Section("ANR", "x".repeat(ProblemReportFormat.MAX_SECTION_CHARS + 10))),
        )
        assertTrue(text.contains("(10 characters cut)"))
    }

    @Test
    fun `crash recorder writes thread and stack`() {
        val file = File(Files.createTempDirectory("crash").toFile(), "d/crash.txt")
        CrashRecorder.record(file, Thread.currentThread(), IllegalStateException("kaboom"))
        val text = file.readText()
        assertTrue(text.contains("thread: ${Thread.currentThread().name}"))
        assertTrue(text.contains("java.lang.IllegalStateException: kaboom"))
    }
}
