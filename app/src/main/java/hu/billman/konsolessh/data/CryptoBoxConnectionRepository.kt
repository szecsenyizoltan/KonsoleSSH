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
 * The stores of 1.x are no longer read; [LegacyStoresCleanup] deletes them.
 *
 * For sync, a save stamps the connection's change time and a delete is
 * recorded in [deletedStore], a separate file.
 */
class CryptoBoxConnectionRepository internal constructor(
    private val store: DataStore<List<ConnectionConfig>>,
    private val deletedStore: DataStore<DeletedConnections>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionRepository {

    override suspend fun load(): List<ConnectionConfig> = read().sortedBy { it.displayName().lowercase() }

    override suspend fun get(id: String): ConnectionConfig? = read().find { it.id == id }

    override suspend fun saveOne(config: ConnectionConfig) {
        val stamped = config.copy(updatedAt = clock())
        update { list ->
            val idx = list.indexOfFirst { it.id == stamped.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = stamped } else list + stamped
        }
    }

    // The deletion is recorded first: if the process dies in between, sync
    // still removes the connection everywhere rather than bringing it back.
    override suspend fun delete(id: String) {
        val now = clock()
        updateDeleted { it.copy(deleted = it.deleted + (id to now)) }
        update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun syncState(): ConnectionSyncState = ConnectionSyncState(read(), readDeleted().deleted)

    override suspend fun replaceFromSync(state: ConnectionSyncState) {
        updateDeleted { DeletedConnections(state.deleted) }
        update { state.connections }
    }

    private suspend fun readDeleted(): DeletedConnections = try {
        deletedStore.data.first()
    } catch (e: IOException) {
        throw ConnectionStoreException("deleted connections are unreadable", e)
    }

    private suspend fun updateDeleted(transform: (DeletedConnections) -> DeletedConnections) {
        try {
            deletedStore.updateData(transform)
        } catch (e: IOException) {
            throw ConnectionStoreException("deleted connections were not changed", e)
        }
    }

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
        private const val DELETED_FILE_NAME = "connections_deleted.json"

        /**
         * The app's connection store. Only one may exist per file and process.
         * [storeFile], [deletedFile], [legacyStores] and [clock] are replaced in tests only.
         */
        internal fun create(
            context: Context,
            cryptoBox: CryptoBox,
            scope: CoroutineScope,
            storeFile: File = context.applicationContext.dataStoreFile(FILE_NAME),
            deletedFile: File = context.applicationContext.dataStoreFile(DELETED_FILE_NAME),
            legacyStores: LegacyStoresCleanup.Stores = LegacyStoresCleanup.Stores(),
            clock: () -> Long = System::currentTimeMillis,
        ): CryptoBoxConnectionRepository {
            val appContext = context.applicationContext
            return CryptoBoxConnectionRepository(
                DataStoreFactory.create(
                    serializer = EncryptedConnectionsSerializer(cryptoBox),
                    migrations = listOf(LegacyStoresCleanup(appContext, legacyStores)),
                    scope = scope,
                    produceFile = { storeFile },
                ),
                DataStoreFactory.create(
                    serializer = DeletedConnectionsSerializer,
                    scope = scope,
                    produceFile = { deletedFile },
                ),
                clock,
            )
        }
    }
}
