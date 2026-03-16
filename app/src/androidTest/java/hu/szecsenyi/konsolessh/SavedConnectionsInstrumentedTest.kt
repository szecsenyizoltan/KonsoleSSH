package hu.szecsenyi.konsolessh

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test — runs on a real Android device or emulator.
 * Verifies SavedConnections behaviour against a real SharedPreferences.
 */
@RunWith(AndroidJUnit4::class)
class SavedConnectionsInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `app package name is correct`() {
        assertEquals("hu.szecsenyi.konsolessh", context.packageName)
    }

    @Test
    fun `save and reload survives process boundary via SharedPreferences`() {
        val cfg = ConnectionConfig(
            id = "instr-1",
            name = "InstrTest",
            host = "192.168.1.1",
            port = 22,
            username = "pi",
            password = "raspberry"
        )
        SavedConnections.saveOne(context, cfg)

        // Simulate fresh load (as if app restarted)
        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        assertEquals("instr-1", loaded[0].id)
        assertEquals("InstrTest", loaded[0].name)
        assertEquals("192.168.1.1", loaded[0].host)
        assertEquals("pi", loaded[0].username)
    }

    @Test
    fun `delete removes correct item on device`() {
        SavedConnections.save(context, listOf(
            ConnectionConfig(id = "keep", host = "h1", username = "u"),
            ConnectionConfig(id = "drop", host = "h2", username = "u")
        ))
        SavedConnections.delete(context, "drop")
        val loaded = SavedConnections.load(context)
        assertEquals(1, loaded.size)
        assertEquals("keep", loaded[0].id)
    }
}
