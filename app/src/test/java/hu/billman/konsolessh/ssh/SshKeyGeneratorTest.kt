package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Pure JVM unit tests for SshKeyGenerator.
 * JSch runs on plain JVM (no Android), tests should not need Robolectric.
 */
class SshKeyGeneratorTest {

    @Test
    fun `generateRsa3072 produces parseable private PEM`() {
        val result = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        assertTrue(
            "private PEM missing header",
            result.privatePem.contains("BEGIN RSA PRIVATE KEY") ||
                result.privatePem.contains("BEGIN OPENSSH PRIVATE KEY") ||
                result.privatePem.contains("BEGIN PRIVATE KEY"),
        )
        // JSch must be able to re-parse what it just wrote
        val jsch = JSch()
        val parsed = KeyPair.load(jsch, result.privatePem.toByteArray(), null)
        assertEquals(KeyPair.RSA, parsed.keyType)
        parsed.dispose()
    }

    @Test
    fun `generateRsa3072 public line starts with ssh-rsa`() {
        val result = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        assertTrue(
            "public line does not start with ssh-rsa: ${result.publicLine.take(40)}",
            result.publicLine.startsWith("ssh-rsa "),
        )
    }

    @Test
    fun `generateRsa3072 public line contains the comment`() {
        val comment = "konsolessh:alice@example.com"
        val result = SshKeyGenerator.generateRsa3072(comment = comment)
        assertTrue(
            "public line does not include comment: ${result.publicLine}",
            result.publicLine.endsWith(comment),
        )
    }

    @Test
    fun `generateRsa3072 public line is single line (no embedded newline)`() {
        val result = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        assertTrue(
            "public line contains newline: <${result.publicLine}>",
            !result.publicLine.contains('\n') && !result.publicLine.contains('\r'),
        )
    }

    @Test
    fun `generateRsa3072 produces different keys on each call`() {
        val a = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        val b = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        assertNotEquals(a.privatePem, b.privatePem)
        assertNotEquals(a.publicLine, b.publicLine)
    }

    @Test
    fun `an Ed25519 key is read back by JSch with the same public key`() {
        val result = SshKeyGenerator.generateEd25519(comment = "unit@test")
        assertTrue(result.privatePem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"))
        val parsed = KeyPair.load(JSch(), result.privatePem.toByteArray(), null)
        assertEquals(KeyPair.ED25519, parsed.keyType)
        assertFalse(parsed.isEncrypted)
        val publicLine = ByteArrayOutputStream().also { parsed.writePublicKey(it, "unit@test") }.toString()
        assertEquals(result.publicLine, publicLine.trim())
        parsed.dispose()
    }

    @Test
    fun `an Ed25519 public line is one line with the comment`() {
        val result = SshKeyGenerator.generateEd25519(comment = "konsolessh:alice@example.com")
        assertTrue(result.publicLine.startsWith("ssh-ed25519 "))
        assertTrue(result.publicLine.endsWith(" konsolessh:alice@example.com"))
        assertFalse(result.publicLine.contains('\n'))
    }

    @Test
    fun `Ed25519 keys differ on each call`() {
        assertNotEquals(
            SshKeyGenerator.generateEd25519(comment = "c").publicLine,
            SshKeyGenerator.generateEd25519(comment = "c").publicLine,
        )
    }

    @Test
    fun `Ed25519 only for OpenSSH 6_5 and Dropbear 2020_79 or later`() {
        val expected = mapOf(
            "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13" to true,
            "SSH-2.0-OpenSSH_6.5" to true,
            "SSH-2.0-OpenSSH_6.4" to false,
            "SSH-2.0-OpenSSH_10.0" to true,
            "SSH-2.0-dropbear_2020.79" to true,
            "SSH-2.0-dropbear_2022.83" to true,
            "SSH-2.0-dropbear_2019.78" to false,
            "SSH-2.0-ROSSSH" to false,
            "SSH-2.0-Cisco-1.25" to false,
            "" to false,
        )
        expected.forEach { (banner, ed25519) ->
            assertEquals(banner, ed25519, SshKeyGenerator.acceptsEd25519(banner))
        }
    }

    @Test
    fun `generateFor picks the key type from the banner`() {
        assertTrue(SshKeyGenerator.generateFor("SSH-2.0-OpenSSH_9.6", "c").publicLine.startsWith("ssh-ed25519 "))
        assertTrue(SshKeyGenerator.generateFor("SSH-2.0-ROSSSH", "c").publicLine.startsWith("ssh-rsa "))
    }

    @Test
    fun `generateRsa3072 key size is 3072 bits`() {
        val result = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        val jsch = JSch()
        val parsed = KeyPair.load(jsch, result.privatePem.toByteArray(), null)
        // JSch exposes keySize for RSA keypairs
        assertEquals(3072, parsed.keySize)
        parsed.dispose()
    }
}
