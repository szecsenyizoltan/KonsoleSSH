package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * The saved SSH connections. Main-safe: every call suspends, and the
 * implementation moves the work off the main thread.
 *
 * An unreadable store (corrupt data, a Keystore failure) throws
 * [ConnectionStoreException], and writes fail too: writing back a list
 * that only looks empty would destroy the saved connections for good.
 */
interface ConnectionRepository {
    suspend fun load(): List<ConnectionConfig>
    suspend fun get(id: String): ConnectionConfig?
    suspend fun saveOne(config: ConnectionConfig)
    suspend fun delete(id: String)
}

/** The store could not be read or written; the stored data is unchanged. */
class ConnectionStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
