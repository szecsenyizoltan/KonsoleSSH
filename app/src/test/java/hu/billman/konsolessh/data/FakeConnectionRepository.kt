package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * Tesztekhez szánt, in-memory ConnectionRepository impl.
 * Nincs Android-függősége, Robolectric-et sem igényel.
 * A sorrendezés (displayName-szerinti) leutánozza a valós impl szemantikáját.
 */
class FakeConnectionRepository(
    initial: List<ConnectionConfig> = emptyList(),
) : ConnectionRepository {

    private val storage: MutableMap<String, ConnectionConfig> =
        initial.associateBy { it.id }.toMutableMap()

    override fun load(): MutableList<ConnectionConfig> =
        storage.values
            .sortedBy { it.displayName().lowercase() }
            .toMutableList()

    override fun save(connections: List<ConnectionConfig>) {
        storage.clear()
        connections.forEach { storage[it.id] = it }
    }

    override fun saveOne(config: ConnectionConfig) {
        storage[config.id] = config
    }

    override fun delete(id: String) {
        storage.remove(id)
    }
}
