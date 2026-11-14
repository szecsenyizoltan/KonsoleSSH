package hu.billman.konsolessh.sync

/** An in-memory sync file, shared by the "devices" of a test. */
class MemoryDrive : SyncFileStore {
    var content: String? = null
    var writes = 0

    /** Thrown by every read, e.g. a missing consent. */
    var failure: Exception? = null

    override suspend fun read(): String? {
        failure?.let { throw it }
        return content
    }

    override suspend fun write(content: String) {
        this.content = content
        writes++
    }
}

class MemoryKeys : SyncKeyStorage {
    var key: SyncKey? = null

    override suspend fun load() = key

    override suspend fun save(key: SyncKey?) {
        this.key = key
    }
}
