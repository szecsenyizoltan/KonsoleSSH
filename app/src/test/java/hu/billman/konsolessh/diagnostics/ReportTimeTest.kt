package hu.billman.konsolessh.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reports must keep the timestamp format they had with SimpleDateFormat. */
class ReportTimeTest {

    @Test
    fun `formats in the given zone with a numeric offset`() {
        val instant = Instant.parse("2026-11-13T22:05:09Z")
        assertEquals("2026-11-13 23:05:09 +0100", ReportTime.format(instant, ZoneId.of("Europe/Budapest")))
        assertEquals("2026-11-13 22:05:09 +0000", ReportTime.format(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `matches the SimpleDateFormat output it replaces`() {
        val instants = listOf(
            "2026-03-29T00:59:59Z",
            "2026-03-29T01:00:00Z",
            "2026-07-01T12:00:00Z",
            "1999-12-31T23:59:59Z",
        ).map(Instant::parse)
        for (zone in listOf("Europe/Budapest", "America/St_Johns", "Asia/Kolkata", "UTC")) {
            val legacy = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone(zone)
            }
            for (instant in instants) {
                assertEquals(legacy.format(Date.from(instant)), ReportTime.format(instant, ZoneId.of(zone)))
            }
        }
    }
}
