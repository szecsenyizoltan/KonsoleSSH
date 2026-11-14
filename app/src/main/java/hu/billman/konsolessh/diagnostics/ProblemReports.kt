package hu.billman.konsolessh.diagnostics

import java.io.File

/**
 * Problem reports from earlier runs: ANRs, from the system's exit history,
 * and uncaught exceptions, recorded by the app. There is no telemetry: the
 * report stays on the device until the user decides to share it.
 */
interface ProblemReports {

    /** Processes new events; returns the report file if there is one the user has not seen. */
    suspend fun collectNew(): File?

    /** The latest report, even if it was already shown. */
    suspend fun latest(): File?

    /** The user has seen the report, and shared or dismissed it. */
    suspend fun markSeen()
}

/** The report's text; pure, so it can be tested. */
object ProblemReportFormat {

    /** Caps one section; an ANR trace holds every thread and can be very long. */
    const val MAX_SECTION_CHARS = 200_000

    data class Section(val title: String, val body: String)

    fun compose(header: String, sections: List<Section>): String = buildString {
        appendLine(header)
        sections.forEach { section ->
            appendLine()
            appendLine("== ${section.title} ==")
            val body = section.body
            if (body.length > MAX_SECTION_CHARS) {
                appendLine(body.take(MAX_SECTION_CHARS))
                appendLine("… (${body.length - MAX_SECTION_CHARS} characters cut)")
            } else {
                appendLine(body)
            }
        }
    }
}
