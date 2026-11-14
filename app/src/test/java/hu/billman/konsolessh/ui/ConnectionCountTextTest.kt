package hu.billman.konsolessh.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import hu.billman.konsolessh.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Counted texts agree with their number: "1 connection", not "1 connections". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionCountTextTest {

    private fun text(language: String, id: Int, count: Int): String {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(config).resources.getQuantityString(id, count, count)
    }

    @Test
    fun `english uses the singular for one`() {
        assertEquals("1 active SSH connection", text("en", R.plurals.service_active, 1))
        assertEquals("3 active SSH connections", text("en", R.plurals.service_active, 3))
        assertEquals("1 connection", text("en", R.plurals.group_connections, 1))
    }

    @Test
    fun `slovak picks the few form for two to four`() {
        assertEquals("3 aktívne SSH pripojenia", text("sk", R.plurals.service_active, 3))
        assertEquals("5 aktívnych SSH pripojení", text("sk", R.plurals.service_active, 5))
    }
}
