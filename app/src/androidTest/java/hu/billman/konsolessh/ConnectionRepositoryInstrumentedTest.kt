package hu.billman.konsolessh

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Eszközön fut, valódi Android Keystore-ral. Saját tárfájlt és kulcsot
 * használ, így a telepített app mentett kapcsolataihoz nem nyúl.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRepositoryInstrumentedTest {

    private lateinit var context: Context

    private fun repo() = CryptoBoxConnectionRepository(
        context,
        AesGcmKeystoreBox(keyAlias = "konsole_instrumented_test_key"),
        prefsName = PREFS,
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(PREFS)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(PREFS)
    }

    @Test
    fun saved_connection_survives_a_new_repository_instance() = runBlocking {
        val cfg = ConnectionConfig(
            id = "instr-1",
            name = "InstrTest",
            host = "192.168.1.1",
            username = "pi",
            password = "raspberry",
        )
        repo().saveOne(cfg)

        val loaded = repo().load()
        assertEquals(listOf(cfg), loaded)
    }

    @Test
    fun delete_removes_only_the_matching_connection() = runBlocking {
        val repo = repo()
        repo.saveOne(ConnectionConfig(id = "keep", host = "h1", username = "u"))
        repo.saveOne(ConnectionConfig(id = "drop", host = "h2", username = "u"))

        repo.delete("drop")

        assertEquals(listOf("keep"), repo().load().map { it.id })
    }

    private companion object {
        const val PREFS = "konsole_connections_instrumented_test"
    }
}
