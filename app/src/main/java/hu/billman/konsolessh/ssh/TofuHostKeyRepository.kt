package hu.billman.konsolessh.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** What the user has to approve: a new or a changed host key. */
data class HostKeyPrompt(
    val host: String,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean,
)

/**
 * One instance per connection. It remembers the result of [check], so the
 * UserInfo.promptYesNo that StrictHostKeyChecking=ask calls next knows from
 * [pending] what to ask, without parsing JSch's English message.
 */
class TofuHostKeyRepository(private val store: HostKeyStore) : HostKeyRepository {

    @Volatile
    var pending: HostKeyPrompt? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val type = keyType(key)
        val sameType = store.entries().filter { it.host == host && it.type == type }
        val result = when {
            sameType.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            sameType.any { decode(it.key)?.contentEquals(key) == true } -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
        pending = if (result == HostKeyRepository.OK) {
            null
        } else {
            HostKeyPrompt(host, type, fingerprint(key), changed = result == HostKeyRepository.CHANGED)
        }
        return result
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        store.put(hostkey.host, hostkey.type, hostkey.key)
    }

    override fun remove(host: String, type: String?) {
        store.remove(host, type?.let(::normalizeType), null)
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        store.remove(host, type?.let(::normalizeType), key?.let { Base64.getEncoder().encodeToString(it) })
    }

    override fun getKnownHostsRepositoryID(): String = REPOSITORY_ID

    override fun getHostKey(): Array<HostKey> = toHostKeys(store.entries())

    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val wanted = type?.let(::normalizeType)
        return toHostKeys(
            store.entries().filter { (host == null || it.host == host) && (wanted == null || it.type == wanted) }
        )
    }

    private fun toHostKeys(entries: List<HostKeyStore.Entry>): Array<HostKey> =
        entries.mapNotNull { entry ->
            decode(entry.key)?.let { runCatching { HostKey(entry.host, HostKey.GUESS, it) }.getOrNull() }
        }.toTypedArray()

    companion object {
        private const val REPOSITORY_ID = "konsolessh_known_hosts"
        private const val LENGTH_PREFIX = 4

        /** The host as JSch names it: the bare host on port 22. */
        fun alias(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

        /** The fingerprint as OpenSSH shows it: `SHA256:` and unpadded base64. */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        /** The key blob's first, length-prefixed field is the algorithm name (RFC 4253). */
        fun keyType(key: ByteArray): String {
            val len = if (key.size < LENGTH_PREFIX) {
                -1
            } else {
                ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
                    ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff)
            }
            val valid = len > 0 && LENGTH_PREFIX + len <= key.size
            return if (valid) String(key, LENGTH_PREFIX, len, Charsets.US_ASCII) else "unknown"
        }

        // The RSA signature algorithms all denote the same ssh-rsa key.
        private fun normalizeType(type: String): String =
            if (type.startsWith("rsa-sha2-")) "ssh-rsa" else type

        private fun decode(key: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(key) }.getOrNull()
    }
}
