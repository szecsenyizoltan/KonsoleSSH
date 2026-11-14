package hu.billman.konsolessh.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncCipherTest {

    // Few iterations keep the tests fast; the count travels in the file either way.
    private val params = SyncCipher.newKeyParams(iterations = 1_000)
    private val key = SyncCipher.deriveKey("correct horse".toCharArray(), params)
    private val secret = "password=hunter2".toByteArray()

    @Test
    fun `a sealed file opens with the same passphrase on another device`() {
        val file = SyncCipher.seal(secret, key, params)
        val joined = SyncCipher.keyParamsOf(file)
        val otherDevice = SyncCipher.deriveKey("correct horse".toCharArray(), joined)
        assertEquals(params, joined)
        assertArrayEquals(secret, SyncCipher.open(file, otherDevice))
    }

    @Test
    fun `the file holds no plaintext`() {
        assertFalse(SyncCipher.seal(secret, key, params).contains("hunter2"))
    }

    @Test(expected = SyncCipher.WrongPassphraseException::class)
    fun `a wrong passphrase is reported as such`() {
        val file = SyncCipher.seal(secret, key, params)
        SyncCipher.open(file, SyncCipher.deriveKey("wrong".toCharArray(), params))
    }

    @Test(expected = SyncCipher.WrongPassphraseException::class)
    fun `a changed header fails authentication`() {
        val file = SyncCipher.seal(secret, key, params)
        SyncCipher.open(file.replace("\"version\":1", "\"version\":0"), key)
    }

    @Test(expected = SyncCipher.UnreadableFileException::class)
    fun `something else is not taken for a sync file`() {
        SyncCipher.open("""{"hello":"world"}""", key)
    }

    @Test(expected = SyncCipher.UnreadableFileException::class)
    fun `a file from a newer format version is refused, not misread`() {
        val newer = SyncCipher.seal(secret, key, params).replace("\"version\":1", "\"version\":2")
        SyncCipher.open(newer, key)
    }

    @Test
    fun `every seal uses a fresh IV`() {
        val a = SyncCipher.seal(secret, key, params)
        val b = SyncCipher.seal(secret, key, params)
        assertFalse(a == b)
    }
}
