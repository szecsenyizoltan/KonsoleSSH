package hu.billman.konsolessh.ssh

import java.io.File

/**
 * Elfogadott szerver host key-ek tára (TOFU, mint az OpenSSH known_hosts).
 * Soronként `host típus base64-kulcs`; a host a JSch-féle alak (`host` vagy
 * `[host]:port`). Egy példány él az appban, mert párhuzamos fülek külön
 * fájlírásai egymás bejegyzéseit írnák felül.
 */
class HostKeyStore(private val file: File) {

    data class Entry(val host: String, val type: String, val key: String)

    private var cache: MutableList<Entry>? = null

    @Synchronized
    fun entries(): List<Entry> = load().toList()

    /** Felvesz egy kulcsot; az azonos hostú és típusú régit lecseréli. */
    @Synchronized
    fun put(host: String, type: String, key: String) {
        val list = load()
        list.removeAll { it.host == host && it.type == type }
        list.add(Entry(host, type, key))
        save(list)
    }

    /** Null típus vagy kulcs: az adott hosthoz bármi illeszkedik. */
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
                if (parts.size == 3) Entry(parts[0], parts[1], parts[2]) else null
            }
            .toMutableList()
    }

    // Átnevezéssel írunk, hogy egy félbeszakadt írás ne hagyjon csonka fájlt.
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
