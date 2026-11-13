package hu.billman.konsolessh.ui

import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.ui.PickerRows.Saved
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerRowsTest {

    private fun cfg(name: String) = ConnectionConfig(id = name, name = name, host = "h", username = "u")

    /** Rows as short strings: "+label(count)" for an open group, "-label(count)" closed, "title" for a connection. */
    private fun List<Saved>.describe() = map { row ->
        "  ".repeat(row.depth) + when (row) {
            is Saved.Group -> "${if (row.expanded) "+" else "-"}${row.label}(${row.count})"
            is Saved.Connection -> row.title
        }
    }

    @Test
    fun `a shared prefix becomes a group, a lone one does not`() {
        val rows = PickerRows.saved(listOf(cfg("prod_web"), cfg("prod_db"), cfg("lab_pi"), cfg("router")), emptySet())
        assertEquals(listOf("-prod(2)", "lab_pi", "router"), rows.describe())
    }

    @Test
    fun `an expanded group lists its children one level deeper, nested groups too`() {
        val connections = listOf(cfg("prod_eu_web"), cfg("prod_eu_db"), cfg("prod_us_web"), cfg("Zeta"))
        val rows = PickerRows.saved(connections, setOf("prod_", "prod_eu_"))
        assertEquals(
            listOf("+prod(3)", "  +eu(2)", "    db", "    web", "  us_web", "Zeta"),
            rows.describe(),
        )
    }

    @Test
    fun `groups come before connections, each alphabetically, ignoring case`() {
        val names = listOf("b", "A", "z_1", "z_2", "c_1", "c_2")
        val rows = PickerRows.saved(names.map(::cfg), emptySet())
        assertEquals(listOf("-c(2)", "-z(2)", "A", "b"), rows.describe())
    }

    @Test
    fun `a connection without a name is shown by user and host`() {
        val rows = PickerRows.saved(listOf(ConnectionConfig(id = "x", host = "10.0.0.1", username = "pi")), emptySet())
        assertEquals("pi@10.0.0.1", (rows.single() as Saved.Connection).title)
    }

    @Test
    fun `open tabs mark the one the pager shows`() {
        val tabs = listOf(
            TabState("t1", "c1", "One", "One", "a.example", ConnectionStatus.CONNECTED),
            TabState("t2", "c2", "Two", "Two", "b.example", ConnectionStatus.CONNECTING),
        )
        val rows = PickerRows.openTabs(tabs, currentPosition = 2)
        assertEquals(listOf(false, true), rows.map { it.active })
        assertEquals(listOf(0, 1), rows.map { it.index })
        assertEquals(ConnectionStatus.CONNECTING, rows[1].status)
    }
}
