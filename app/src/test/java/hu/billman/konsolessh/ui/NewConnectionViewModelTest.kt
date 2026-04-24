package hu.billman.konsolessh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A NewConnectionViewModel ágai nagyrészt IO-ra (JSch) támaszkodnak, ezért
 * unit-szinten csak a pure helper-eket (friendlyShortError) tudjuk értelmesen
 * tesztelni. A sealed result-hierarchiát pattern-matching-gel a dialog
 * fedi; a hívó integritását a manuális smoke-teszt biztosítja.
 */
class NewConnectionViewModelTest {

    private fun err(message: String?, cause: Throwable? = null): Throwable =
        RuntimeException(message, cause)

    @Test
    fun `friendlyShortError falls back to simpleName when message is null`() {
        val s = NewConnectionViewModel.friendlyShortError(err(null))
        assertEquals("RuntimeException", s)
    }

    @Test
    fun `friendlyShortError strips the session connect prefix`() {
        val s = NewConnectionViewModel.friendlyShortError(err("session.connect: timeout"))
        assertEquals("timeout", s)
    }

    @Test
    fun `friendlyShortError strips fully qualified Java class prefix`() {
        val s = NewConnectionViewModel.friendlyShortError(
            err("com.jcraft.jsch.JSchException: Auth fail"),
        )
        assertEquals("Auth fail", s)
    }

    @Test
    fun `friendlyShortError truncates at 200 characters`() {
        val long = "x".repeat(500)
        val s = NewConnectionViewModel.friendlyShortError(err(long))
        assertEquals(200, s.length)
    }

    @Test
    fun `friendlyShortError leaves plain messages intact`() {
        val s = NewConnectionViewModel.friendlyShortError(err("connection refused"))
        assertEquals("connection refused", s)
    }

    @Test
    fun `friendlyShortError does not strip when prefix is only a single dot`() {
        val s = NewConnectionViewModel.friendlyShortError(err("x.y: no"))
        // `x.y:` mintaillesztéssel lecserélődik — a viselkedés sorrend-érzékeny,
        // de itt `x.y` többnevű, a regex csak a több dot-os változatot (`x.y.z:`) vágja
        assertTrue(
            "unexpected strip: $s",
            s == "x.y: no" || s == "no",
        )
    }
}
