package hu.billman.konsolessh.data

/**
 * Encryption for the connection store, behind an interface so the
 * repository tests can use a fake and need neither Robolectric nor a
 * Keystore.
 *
 * The payload is opaque bytes. An implementation owns the key, generates
 * a fresh IV for every encryption, authenticates the data (AEAD) and
 * reports a payload it cannot decrypt with an exception.
 */
interface CryptoBox {
    /** The output carries the IV and the authentication tag as well. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Reverses [encrypt]; throws if the payload was altered or the key is gone. */
    fun decrypt(payload: ByteArray): ByteArray
}
