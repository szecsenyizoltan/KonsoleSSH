package hu.billman.konsolessh.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of the sync file. The Keystore key that protects
 * the local store cannot leave the device, so the copy in Google Drive is
 * encrypted with a key derived from the user's sync passphrase instead:
 * Google only ever stores ciphertext.
 *
 * The file is a JSON envelope. The salt and iteration count travel in it,
 * so every device derives the same key from the same passphrase; they are
 * authenticated together with the ciphertext, so they cannot be changed
 * unnoticed.
 */
object SyncCipher {

    const val ITERATIONS = 600_000
    private const val FORMAT = "konsolessh-sync"
    private const val VERSION = 1
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** The passphrase does not open this file, or the file was altered. */
    class WrongPassphraseException : GeneralSecurityException("the sync passphrase does not open this file")

    /** The data is not a sync file this version can read. */
    class UnreadableFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val data: String,
    )

    /** What a device needs, besides the passphrase, to derive the file's key. */
    data class KeyParams(val salt: ByteArray, val iterations: Int) {
        override fun equals(other: Any?) =
            other is KeyParams && salt.contentEquals(other.salt) && iterations == other.iterations

        override fun hashCode() = 31 * salt.contentHashCode() + iterations
    }

    fun newKeyParams(iterations: Int = ITERATIONS): KeyParams =
        KeyParams(ByteArray(SALT_BYTES).also(random::nextBytes), iterations)

    /** Slow on purpose (about a second on a phone): call it off the main thread, once per device. */
    fun deriveKey(passphrase: CharArray, params: KeyParams): SecretKey {
        val spec = PBEKeySpec(passphrase, params.salt, params.iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** The key parameters of an existing file, so a joining device can derive its key. */
    fun keyParamsOf(file: String): KeyParams {
        val envelope = parse(file)
        return KeyParams(decode(envelope.salt), envelope.iterations)
    }

    fun seal(plaintext: ByteArray, key: SecretKey, params: KeyParams): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = Envelope(FORMAT, VERSION, KDF, params.iterations, encode(params.salt), encode(iv), "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(header))
        return json.encodeToString(Envelope.serializer(), header.copy(data = encode(cipher.doFinal(plaintext))))
    }

    fun open(file: String, key: SecretKey): ByteArray {
        val envelope = parse(file)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(envelope.iv)))
        cipher.updateAAD(aad(envelope.copy(data = "")))
        return try {
            cipher.doFinal(decode(envelope.data))
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException().apply { initCause(e) }
        }
    }

    private fun parse(file: String): Envelope {
        // SerializationException is an IllegalArgumentException: one catch covers both.
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), file)
        } catch (e: IllegalArgumentException) {
            throw UnreadableFileException("not a sync file", e)
        }
        val problem = when {
            envelope.format != FORMAT || envelope.kdf != KDF -> "not a sync file"
            envelope.version > VERSION -> "written by a newer version"
            else -> return envelope
        }
        throw UnreadableFileException(problem)
    }

    // Everything but the ciphertext is authenticated, in a fixed order.
    private fun aad(header: Envelope): ByteArray =
        listOf(header.format, header.version, header.kdf, header.iterations, header.salt, header.iv)
            .joinToString("|").toByteArray(Charsets.UTF_8)

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw UnreadableFileException("damaged sync file", e)
    }
}
