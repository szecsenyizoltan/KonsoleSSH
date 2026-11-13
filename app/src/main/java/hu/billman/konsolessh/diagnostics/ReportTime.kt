package hu.billman.konsolessh.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Timestamps in crash and problem reports, in the device's time zone. */
internal object ReportTime {

    private val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)

    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        pattern.withZone(zone).format(instant)
}
