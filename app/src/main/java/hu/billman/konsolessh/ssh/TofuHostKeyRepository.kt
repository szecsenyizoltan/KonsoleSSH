package hu.billman.konsolessh.ssh

import android.os.Handler
import android.os.Looper
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Amit a felhasználónak jóvá kell hagynia: új vagy megváltozott host key. */
data class HostKeyPrompt(
    val host: String,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean,
)

/** Főszálon hívódik; a callback true-val fogadja el a kulcsot. */
typealias HostKeyPrompterFn = (prompt: HostKeyPrompt, callback: (Boolean) -> Unit) -> Unit

/**
 * Kapcsolatonként egy példány: a [check] eredményét megjegyzi, így a
 * StrictHostKeyChecking=ask miatt utána hívott UserInfo.promptYesNo a JSch
 * angol szövegének értelmezése nélkül tudja, mit kell megkérdezni.
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

    /** A függő kérdést a főszálon felteszi; időtúllépés vagy hiányzó UI esetén elutasít. */
    fun confirmPending(prompter: HostKeyPrompterFn?): Boolean {
        val prompt = pending ?: return false
        val latch = CountDownLatch(1)
        var accepted = false
        Handler(Looper.getMainLooper()).post {
            prompter?.invoke(prompt) { answer ->
                accepted = answer
                latch.countDown()
            } ?: latch.countDown()
        }
        val answered = latch.await(PROMPT_TIMEOUT_S, TimeUnit.SECONDS)
        return answered && accepted
    }

    private fun toHostKeys(entries: List<HostKeyStore.Entry>): Array<HostKey> =
        entries.mapNotNull { entry ->
            decode(entry.key)?.let { runCatching { HostKey(entry.host, HostKey.GUESS, it) }.getOrNull() }
        }.toTypedArray()

    companion object {
        private const val REPOSITORY_ID = "konsolessh_known_hosts"
        private const val PROMPT_TIMEOUT_S = 120L
        private const val LENGTH_PREFIX = 4

        /** JSch-kompatibilis host-azonosító: 22-es portnál a puszta host. */
        fun alias(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

        /** OpenSSH-formátumú ujjlenyomat: `SHA256:` + padding nélküli base64. */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        /** A kulcsblob első, hosszelőtagos mezője az algoritmus neve (RFC 4253). */
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

        // Az RSA aláírási algoritmusok ugyanazt az ssh-rsa kulcsot jelentik.
        private fun normalizeType(type: String): String =
            if (type.startsWith("rsa-sha2-")) "ssh-rsa" else type

        private fun decode(key: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(key) }.getOrNull()
    }
}

/**
 * UserInfo a rövid életű próbakapcsolatokhoz: csak a host key kérdést teszi
 * fel, jelszót és passphrase-t nem kér újra.
 */
class HostKeyOnlyUserInfo(
    private val repository: TofuHostKeyRepository,
    private val prompter: HostKeyPrompterFn?,
) : UserInfo {
    override fun promptYesNo(message: String): Boolean = repository.confirmPending(prompter)
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String): Boolean = false
    override fun promptPassphrase(message: String): Boolean = false
    override fun showMessage(message: String) = Unit
}
