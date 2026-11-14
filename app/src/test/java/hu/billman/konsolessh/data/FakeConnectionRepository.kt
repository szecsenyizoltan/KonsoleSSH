package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * An in-memory ConnectionRepository for tests. Its order (by display name)
 * and its failures follow the real one: with [unreadable], every call
 * throws [ConnectionStoreException].
 */
class FakeConnectionRepository(
    initial: List<ConnectionConfig> = emptyList(),
    private val clock: () -> Long = { 0L },
) : ConnectionRepository {

    private val storage: MutableMap<String, ConnectionConfig> = initial.associateBy { it.id }.toMutableMap()

    var unreadable = false

    override suspend fun load(): List<ConnectionConfig> {
        check()
        return storage.values.sortedBy { it.displayName().lowercase() }
    }

    override suspend fun get(id: String): ConnectionConfig? {
        check()
        return storage[id]
    }

    override suspend fun saveOne(config: ConnectionConfig) {
        check()
        storage[config.id] = config
    }

    override suspend fun delete(id: String) {
        check()
        storage.remove(id)
        deleted[id] = clock()
    }

    val deleted = mutableMapOf<String, Long>()

    override suspend fun syncState(): ConnectionSyncState {
        check()
        return ConnectionSyncState(storage.values.toList(), deleted.toMap())
    }

    override suspend fun replaceFromSync(state: ConnectionSyncState) {
        check()
        storage.clear()
        state.connections.forEach { storage[it.id] = it }
        deleted.clear()
        deleted.putAll(state.deleted)
    }

    private fun check() {
        if (unreadable) throw ConnectionStoreException("unreadable")
    }
}
