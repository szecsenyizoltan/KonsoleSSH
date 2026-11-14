package hu.billman.konsolessh.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [CryptoBox] with AES-256-GCM under an Android Keystore key.
 *
 * The key is created once, on first use, and found by its alias afterwards.
 * It stays in the Keystore (hardware-backed where available), so its bytes
 * are never readable from the app's process.
 *
 * Payload: `[IV (12 bytes)][ciphertext + GCM tag (16 bytes)]`. The cipher
 * generates a fresh IV for every encryption, so a nonce is never reused.
 *
 * Every JCE exception propagates; the repository turns it into a
 * [ConnectionStoreException].
 */
class AesGcmKeystoreBox(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CryptoBox {

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Unexpected IV size ${iv.size}, want $IV_SIZE" }
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_SIZE + TAG_SIZE) { "payload too short" }
        val iv = payload.copyOfRange(0, IV_SIZE)
        val ciphertext = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "konsolessh_connections_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        private const val TAG_SIZE_BITS = 128
    }
}
