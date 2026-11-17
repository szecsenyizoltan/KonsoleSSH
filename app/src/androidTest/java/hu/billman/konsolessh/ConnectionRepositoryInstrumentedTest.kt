package hu.billman.konsolessh

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.data.LegacyStoresCleanup
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a device, with the real Android Keystore. It uses its own store
 * files and key, so the installed app's saved connections are left alone.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRepositoryInstrumentedTest {

    private lateinit var context: Context
    private lateinit var file: File

    private fun deletedFile() = File(file.parentFile, file.name + ".deleted")
    private val scopes = mutableListOf<CoroutineScope>()

    private fun closeScopes() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    /**
     * One DataStore per file at a time: a new repository closes the previous
     * one first, and waits for it, since DataStore releases the file only when
     * its scope has completed.
     */
    private fun repo(): CryptoBoxConnectionRepository {
        closeScopes()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return CryptoBoxConnectionRepository.create(
            context,
            AesGcmKeystoreBox(keyAlias = "konsole_instrumented_test_key"),
            scope,
            storeFile = file,
            deletedFile = deletedFile(),
            // The cleanup deletes the old stores: never let it see the app's real ones.
            legacyStores = LegacyStoresCleanup.Stores(
                box = PREFS,
                encrypted = "${PREFS}_enc",
                plain = "${PREFS}_plain",
            ),
        )
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.filesDir, "datastore/connections_instrumented_test.pb")
        file.delete()
        deletedFile().delete()
        context.deleteSharedPreferences(PREFS)
    }

    @After
    fun tearDown() {
        closeScopes()
        file.delete()
        deletedFile().delete()
        context.deleteSharedPreferences(PREFS)
    }

    @Test
    fun saved_connection_survives_a_new_repository_instance() = runBlocking {
        val cfg = ConnectionConfig(
            id = "instr-1",
            name = "InstrTest",
            host = "192.168.1.1",
            username = "pi",
            auth = ConnectionConfig.Auth.Password("raspberry"),
        )
        repo().saveOne(cfg)

        // The save stamps the change time; everything else must come back as written.
        val loaded = repo().load()
        assertEquals(listOf(cfg), loaded.map { it.copy(updatedAt = 0L) })
        assertTrue(loaded.single().updatedAt > 0L)
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
