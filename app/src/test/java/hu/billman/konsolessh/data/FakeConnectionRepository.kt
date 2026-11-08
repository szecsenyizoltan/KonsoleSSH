package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * Tesztekhez szánt, in-memory ConnectionRepository impl. A sorrend
 * (displayName szerint) és a hibaviselkedés a valós impl szemantikáját
 * követi: [unreadable] esetén minden művelet [ConnectionStoreException]-t dob.
 */
class FakeConnectionRepository(
    initial: List<ConnectionConfig> = emptyList(),
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
    }

    private fun check() {
        if (unreadable) throw ConnectionStoreException("unreadable")
    }
}
