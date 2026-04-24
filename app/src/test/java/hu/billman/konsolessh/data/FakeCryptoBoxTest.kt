package hu.billman.konsolessh.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure JVM teszt — a FakeCryptoBox a repository-tesztek alaptégla, a
 * kontraktust (round-trip + bad-payload detection) rögzítjük.
 */
class FakeCryptoBoxTest {

    @Test
    fun `decrypt after encrypt returns original bytes`() {
        val box = FakeCryptoBox()
        val plaintext = "hello world 🌍".toByteArray(Charsets.UTF_8)
        val encrypted = box.encrypt(plaintext)
        val decrypted = box.decrypt(encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted bytes differ from plaintext`() {
        val box = FakeCryptoBox()
        val plaintext = byteArrayOf(1, 2, 3, 4)
        val encrypted = box.encrypt(plaintext)
        // Different length due to magic prefix, different content due to XOR mask
        assertNotEquals(plaintext.toList(), encrypted.toList())
    }

    @Test
    fun `decrypt rejects payload with bad magic`() {
        val box = FakeCryptoBox()
        val bogus = byteArrayOf(0, 0, 0, 0, 1, 2, 3)
        assertThrows(IllegalArgumentException::class.java) {
            box.decrypt(bogus)
        }
    }

    @Test
    fun `decrypt rejects too-short payload`() {
        val box = FakeCryptoBox()
        assertThrows(IllegalArgumentException::class.java) {
            box.decrypt(byteArrayOf(1))
        }
    }

    @Test
    fun `empty plaintext round trips`() {
        val box = FakeCryptoBox()
        val encrypted = box.encrypt(byteArrayOf())
        assertArrayEquals(byteArrayOf(), box.decrypt(encrypted))
    }
}
