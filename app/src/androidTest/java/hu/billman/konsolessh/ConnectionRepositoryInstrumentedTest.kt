package hu.billman.konsolessh

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Eszközön fut, valódi Android Keystore-ral. Saját tárfájlt és kulcsot
 * használ, így a telepített app mentett kapcsolataihoz nem nyúl.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()

    /** One DataStore per file at a time: a new repository closes the previous one first. */
    private fun repo(): CryptoBoxConnectionRepository {
        scopes.forEach { it.cancel() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return CryptoBoxConnectionRepository.create(
            context,
            AesGcmKeystoreBox(keyAlias = "konsole_instrumented_test_key"),
            scope,
            storeFile = file,
            legacyPrefsName = PREFS,
        )
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.filesDir, "datastore/connections_instrumented_test.pb")
        file.delete()
        context.deleteSharedPreferences(PREFS)
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
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
