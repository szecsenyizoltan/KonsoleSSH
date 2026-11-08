package hu.billman.konsolessh.diagnostics

import java.io.File

/**
 * Hibajelentések az előző futásokból: ANR (a rendszer kilépési előzményeiből)
 * és nem kezelt kivétel (saját rögzítéssel). Telemetria nincs: a jelentés a
 * készüléken marad, a felhasználó dönt a megosztásról.
 */
interface ProblemReports {

    /** Feldolgozza az új eseményeket; ha van meg nem mutatott jelentés, annak fájlja. */
    suspend fun collectNew(): File?

    /** Az utolsó jelentés, akkor is, ha már megmutattuk. */
    suspend fun latest(): File?

    /** A felhasználó látta a jelentést (megosztotta vagy elutasította). */
    suspend fun markSeen()
}

/** A jelentés szövege; tiszta függvény, hogy tesztelhető legyen. */
object ProblemReportFormat {

    /** Ennyi karakter fér egy szakaszba; egy ANR-nyom minden szálat tartalmaz. */
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
