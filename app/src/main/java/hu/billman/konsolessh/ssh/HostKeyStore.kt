package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.data.writeAtomically
import java.io.File

/**
 * The accepted server host keys (trust on first use, like OpenSSH's
 * known_hosts). One `host type base64-key` per line; the host is in JSch's
 * form (`host` or `[host]:port`). The app has one instance, because
 * parallel tabs writing the file separately would overwrite each other's
 * entries.
 *
 * For sync, the time of each acceptance, replacement and removal is kept
 * in a second file next to it, `<file>.times`, so known_hosts itself keeps
 * its format.
 */
class HostKeyStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after the user's own changes (not after [replaceFromSync]), to start a sync. */
    private val onChange: () -> Unit = {},
) {

    data class Entry(val host: String, val type: String, val key: String)

    /** A host key as sync sees it, with its last change; a null [key] is a removal. */
    data class Change(val host: String, val type: String, val key: String?, val at: Long)

    private var cache: MutableList<Entry>? = null
    private var timesCache: MutableMap<Pair<String, String>, Change>? = null
    private val timesFile = File(file.path + ".times")

    @Synchronized
    fun entries(): List<Entry> = load().toList()

    /** Adds a key, replacing an older one of the same host and type. */
    @Synchronized
    fun put(host: String, type: String, key: String) {
        val list = load()
        list.removeAll { it.host == host && it.type == type }
        list.add(Entry(host, type, key))
        save(list)
        recordChanges(listOf(Change(host, type, key, clock())))
        onChange()
    }

    /** A null type or key matches anything stored for the host. */
    @Synchronized
    fun remove(host: String, type: String?, key: String?) {
        val list = load()
        val gone = list.filter {
            it.host == host && (type == null || it.type == type) && (key == null || it.key == key)
        }
        if (gone.isEmpty()) return
        list.removeAll(gone)
        save(list)
        val now = clock()
        recordChanges(gone.map { Change(it.host, it.type, null, now) })
        onChange()
    }

    /** Every stored key and every removal, with their times; keys from before 2.2.0 have time 0. */
    @Synchronized
    fun changes(): List<Change> {
        val times = loadTimes()
        val present = load().map { entry ->
            val known = times[entry.host to entry.type]?.takeIf { it.key == entry.key }
            Change(entry.host, entry.type, entry.key, known?.at ?: 0L)
        }
        val removed = times.values.filter { it.key == null }
        return present + removed
    }

    /** Replaces the keys and their times with a merged state from sync. */
    @Synchronized
    fun replaceFromSync(changes: List<Change>) {
        save(changes.mapNotNull { c -> c.key?.let { Entry(c.host, c.type, it) } }.toMutableList())
        timesCache = changes.associateBy { it.host to it.type }.toMutableMap()
        saveTimes()
    }

    private fun recordChanges(changes: List<Change>) {
        val times = loadTimes()
        changes.forEach { times[it.host to it.type] = it }
        saveTimes()
    }

    private fun load(): MutableList<Entry> = cache ?: read().also { cache = it }

    private fun read(): MutableList<Entry> {
        if (!file.exists()) return mutableListOf()
        return file.readLines()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                // A damaged line must not turn into a bogus trusted key.
                val valid = parts.size == 3 && KEY_TYPE.matches(parts[1]) && BASE64.matches(parts[2])
                if (valid) Entry(parts[0], parts[1], parts[2]) else null
            }
            .toMutableList()
    }

    // `host type time key`, or `host type time -` for a removal.
    private fun loadTimes(): MutableMap<Pair<String, String>, Change> = timesCache ?: readTimes().also { timesCache = it }

    private fun readTimes(): MutableMap<Pair<String, String>, Change> {
        if (!timesFile.exists()) return mutableMapOf()
        return timesFile.readLines()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                val at = parts.getOrNull(2)?.toLongOrNull()
                val key = parts.getOrNull(3)?.takeIf { it != REMOVED }
                if (parts.size == 4 && at != null && KEY_TYPE.matches(parts[1])) {
                    Change(
                        parts[0],
                        parts[1],
                        key,
                        at
                    )
                } else {
                    null
                }
            }
            .associateBy { it.host to it.type }
            .toMutableMap()
    }

    private fun saveTimes() {
        val lines = loadTimes().values.joinToString("") { "${it.host} ${it.type} ${it.at} ${it.key ?: REMOVED}\n" }
        timesFile.writeAtomically(lines.toByteArray(Charsets.UTF_8))
    }

    // The cache follows the file only after a successful write, so a failed
    // write cannot leave a key trusted in memory that the disk does not have.
    private fun save(list: MutableList<Entry>) {
        file.writeAtomically(list.joinToString("") { "${it.host} ${it.type} ${it.key}\n" }.toByteArray(Charsets.UTF_8))
        cache = list
    }

    private companion object {
        val KEY_TYPE = Regex("(ssh|ecdsa-sha2|sk|rsa-sha2)-[A-Za-z0-9@.-]+")
        val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
        const val REMOVED = "-"
    }
}
