package hu.billman.konsolessh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

/**
 * Saved connections in a DataStore file, encrypted as a whole with a
 * [CryptoBox] (AES-256-GCM on the Android Keystore in the app).
 *
 * DataStore writes to a temporary file and renames it, so a write either
 * replaces the whole store or leaves it as it was.
 *
 * An unreadable store (damaged file, lost or changed Keystore key) raises
 * [ConnectionStoreException] on every read and write. It is never replaced
 * with an empty list, because writing that back would destroy the saved
 * connections for good.
 *
 * Stores of older versions are moved in once by [LegacyConnectionsMigration].
 */
class CryptoBoxConnectionRepository internal constructor(
    private val store: DataStore<List<ConnectionConfig>>,
) : ConnectionRepository {

    override suspend fun load(): List<ConnectionConfig> = read().sortedBy { it.displayName().lowercase() }

    override suspend fun get(id: String): ConnectionConfig? = read().find { it.id == id }

    override suspend fun saveOne(config: ConnectionConfig) = update { list ->
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list.toMutableList().also { it[idx] = config } else list + config
    }

    override suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun read(): List<ConnectionConfig> = try {
        store.data.first()
    } catch (e: IOException) {
        throw ConnectionStoreException("stored connections are unreadable", e)
    }

    private suspend fun update(transform: (List<ConnectionConfig>) -> List<ConnectionConfig>) {
        try {
            store.updateData(transform)
        } catch (e: IOException) {
            throw ConnectionStoreException("the connection store was not changed", e)
        }
    }

    companion object {
        private const val FILE_NAME = "connections.pb"

        /**
         * The app's connection store. Only one may exist per file and process.
         * [storeFile] and [legacyPrefsName] are replaced in tests only.
         */
        fun create(
            context: Context,
            cryptoBox: CryptoBox,
            scope: CoroutineScope,
            storeFile: File = context.applicationContext.dataStoreFile(FILE_NAME),
            legacyPrefsName: String = LegacyConnectionsMigration.PREFS_NAME,
        ): CryptoBoxConnectionRepository {
            val appContext = context.applicationContext
            return CryptoBoxConnectionRepository(
                DataStoreFactory.create(
                    serializer = EncryptedConnectionsSerializer(cryptoBox),
                    migrations = listOf(LegacyConnectionsMigration(appContext, cryptoBox, legacyPrefsName)),
                    scope = scope,
                    produceFile = { storeFile },
                ),
            )
        }
    }
}
