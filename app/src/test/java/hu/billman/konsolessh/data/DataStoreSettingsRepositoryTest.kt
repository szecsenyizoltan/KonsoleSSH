package hu.billman.konsolessh.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.filesDir, "datastore/settings_test.preferences_pb")
        file.delete()
        context.deleteSharedPreferences(LEGACY)
    }

    @After
    fun tearDown() {
        closeScopes()
        file.delete()
        context.deleteSharedPreferences(LEGACY)
    }

    private fun closeScopes() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    /**
     * One DataStore per file at a time: a new repository closes the previous
     * one first, and waits for it, since DataStore releases the file only when
     * its scope has completed.
     */
    private fun newRepo(): DataStoreSettingsRepository {
        closeScopes()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DataStoreSettingsRepository(DataStoreSettingsRepository.createStore(context, scope, LEGACY) { file })
    }

    @Test
    fun `nothing saved reads as zero`() = runBlocking {
        assertEquals(0f, newRepo().fontSizeSp.first())
    }

    @Test
    fun `a saved size survives a new store instance`() = runBlocking {
        newRepo().setFontSizeSp(15.5f)
        assertEquals(15.5f, newRepo().fontSizeSp.first())
    }

    @Test
    fun `the size saved by older versions is migrated and the old preferences are cleared`() = runBlocking {
        context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().putFloat("font_size", 14f).commit()

        assertEquals(14f, newRepo().fontSizeSp.first())
        assertFalse(context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).contains("font_size"))
        assertEquals(14f, newRepo().fontSizeSp.first())
    }

    @Test
    fun `a corrupt settings file is replaced, and saving works again`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
        val repo = newRepo()
        assertEquals(0f, repo.fontSizeSp.first())

        repo.setFontSizeSp(13f)
        assertEquals(13f, newRepo().fontSizeSp.first())
    }

    private companion object {
        const val LEGACY = "settings_test_legacy"
    }
}
