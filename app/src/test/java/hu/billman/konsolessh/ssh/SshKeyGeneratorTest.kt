package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
    fun `generateRsa3072 key size is 3072 bits`() {
        val result = SshKeyGenerator.generateRsa3072(comment = "unit@test")
        val jsch = JSch()
        val parsed = KeyPair.load(jsch, result.privatePem.toByteArray(), null)
        // JSch exposes keySize for RSA keypairs
        assertEquals(3072, parsed.keySize)
        parsed.dispose()
    }
}
