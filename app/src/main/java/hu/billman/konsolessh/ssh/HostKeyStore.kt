package hu.billman.konsolessh.ssh

import java.io.File

/**
 * The accepted server host keys (trust on first use, like OpenSSH's
 * known_hosts). One `host type base64-key` per line; the host is in JSch's
 * form (`host` or `[host]:port`). The app has one instance, because
 * parallel tabs writing the file separately would overwrite each other's
 * entries.
 */
class HostKeyStore(private val file: File) {

    data class Entry(val host: String, val type: String, val key: String)

    private var cache: MutableList<Entry>? = null

    @Synchronized
    fun entries(): List<Entry> = load().toList()

    /** Adds a key, replacing an older one of the same host and type. */
    @Synchronized
    fun put(host: String, type: String, key: String) {
        val list = load()
        list.removeAll { it.host == host && it.type == type }
        list.add(Entry(host, type, key))
        save(list)
    }

    /** A null type or key matches anything stored for the host. */
    @Synchronized
    fun remove(host: String, type: String?, key: String?) {
        val list = load()
        val removed = list.removeAll {
            it.host == host && (type == null || it.type == type) && (key == null || it.key == key)
        }
        if (removed) save(list)
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

    private companion object {
        val KEY_TYPE = Regex("(ssh|ecdsa-sha2|sk|rsa-sha2)-[A-Za-z0-9@.-]+")
        val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
    }

    // Written through a rename, so an interrupted write leaves no truncated file.
    private fun save(list: List<Entry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(list.joinToString("") { "${it.host} ${it.type} ${it.key}\n" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
