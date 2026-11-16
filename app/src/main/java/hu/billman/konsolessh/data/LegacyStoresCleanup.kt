package hu.billman.konsolessh.data

import android.content.Context
import androidx.datastore.core.DataMigration
import hu.billman.konsolessh.model.ConnectionConfig
import java.io.File

/**
 * Deletes the connection stores of 1.x, unread. Version 2.x moved them into
 * the DataStore file and deleted them; they remain only on a device that went
 * from 1.x straight to 3.0.0 or later, where the connections have to be
 * entered again. The oldest of those stores could hold passwords in
 * plaintext, so they are not left behind.
 *
 * It runs as a DataStore migration so the file checks happen on DataStore's
 * own thread, before the first read, and the store's content is not changed.
 */
internal class LegacyStoresCleanup(
    private val context: Context,
    private val stores: Stores = Stores(),
) : DataMigration<List<ConnectionConfig>> {

    /**
     * SharedPreferences names of the 1.x stores. Tests that run inside the
     * installed app pass their own names, so they never delete the app's files.
     */
    data class Stores(
        val box: String = "konsole_connections_box",
        val encrypted: String = "konsole_connections_enc",
        val plain: String = "konsole_connections",
    ) {
        val all get() = listOf(box, encrypted, plain)
    }

    override suspend fun shouldMigrate(currentData: List<ConnectionConfig>): Boolean =
        stores.all.any { prefsFile(it).exists() }

    override suspend fun migrate(currentData: List<ConnectionConfig>): List<ConnectionConfig> = currentData

    override suspend fun cleanUp() {
        stores.all.forEach { name -> runCatching { context.deleteSharedPreferences(name) } }
    }

    // Checked as files: opening SharedPreferences would create the file.
    private fun prefsFile(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
}
