package hu.billman.konsolessh.data

/**
 * Vékony kriptó-absztrakció a repository-rétegnek.
 *
 * A perzisztált formátum egy opaque bájtsor (a hívó Base64-ben vagy
 * más módon szöveggé alakíthatja). Az impl felelőssége:
 *   - kulcskezelés (Android Keystore, hardware-backed ha lehetséges)
 *   - IV/nonce-generálás minden encrypt-nél
 *   - autentikáció (AEAD-címke)
 *   - dekódolás-hibák jelzése kivétellel
 *
 * A tesztek [FakeCryptoBox]-t (identity / in-memory XOR) használnak,
 * így a repository-tesztek nem igényelnek Robolectric-et vagy
 * Keystore-emulációt.
 */
interface CryptoBox {
    /** Titkosít egy payload-ot; a kimenet tartalmazza az IV-t és a címkét is. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Dekódol egy korábban [encrypt]-tel készült payload-ot. Kivételt dob hibára. */
    fun decrypt(payload: ByteArray): ByteArray
}
