package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates key pairs for authorized_keys authentication: Ed25519 where the
 * server's banner shows a version known to accept it, RSA-3072 everywhere
 * else. MikroTik RouterOS stays on RSA because its Ed25519 support for user
 * keys depends on the RouterOS version, which the banner does not show.
 *
 * Free of Android dependencies, so it is unit-tested on the JVM.
 */
object SshKeyGenerator {

    data class GeneratedKey(
        val privatePem: String,
        val publicLine: String,
    )

    // Ed25519 arrived in OpenSSH 6.5 and Dropbear 2020.79.
    private val OPENSSH = Regex("""OpenSSH_(\d+)\.(\d+)""")
    private val DROPBEAR = Regex("""dropbear_(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)
    private val OPENSSH_ED25519 = 6 to 5
    private val DROPBEAR_ED25519 = 2020 to 79
    private const val PEM_LINE = 70

    /** The key type for the server that sent [serverVersion], see [acceptsEd25519]. */
    fun generateFor(serverVersion: String, comment: String): GeneratedKey =
        if (acceptsEd25519(serverVersion)) generateEd25519(comment) else generateRsa3072(comment)

    fun acceptsEd25519(serverVersion: String): Boolean {
        val openSsh = OPENSSH.find(serverVersion)
        val dropbear = DROPBEAR.find(serverVersion)
        return when {
            openSsh != null -> openSsh.version() >= OPENSSH_ED25519
            dropbear != null -> dropbear.version() >= DROPBEAR_ED25519
            else -> false
        }
    }

    private fun MatchResult.version(): Pair<Int, Int> =
        groupValues[1].toInt() to groupValues[2].toInt()

    private operator fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int =
        compareValuesBy(this, other, { it.first }, { it.second })

    /**
     * JSch reads Ed25519 private keys but cannot write them, so the key is
     * generated and encoded in the openssh-key-v1 format with Bouncy Castle.
     */
    fun generateEd25519(comment: String): GeneratedKey {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val body = Base64.getEncoder()
            .encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private))
            .chunked(PEM_LINE)
            .joinToString("\n")
        val publicBlob = Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pair.public))
        return GeneratedKey(
            privatePem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n",
            publicLine = "ssh-ed25519 $publicBlob $comment",
        )
    }

    fun generateRsa3072(comment: String): GeneratedKey {
        val jsch = JSch()
        val kpair = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        val privBuf = ByteArrayOutputStream()
        val pubBuf = ByteArrayOutputStream()
        try {
            kpair.writePrivateKey(privBuf)
            kpair.writePublicKey(pubBuf, comment)
        } finally {
            kpair.dispose()
        }
        return GeneratedKey(
            privatePem = String(privBuf.toByteArray(), Charsets.UTF_8),
            publicLine = String(pubBuf.toByteArray(), Charsets.UTF_8).trim(),
        )
    }
}
