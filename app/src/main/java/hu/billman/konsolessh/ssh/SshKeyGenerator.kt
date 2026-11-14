package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/**
 * Generates RSA key pairs for authorized_keys authentication. RSA because
 * every target (Linux, MikroTik, Dropbear) accepts it; Ed25519 would need an
 * EdDSA provider in the Android runtime, which cannot be relied on.
 *
 * Free of Android dependencies, so it is unit-tested on the JVM.
 */
object SshKeyGenerator {

    data class GeneratedKey(
        val privatePem: String,
        val publicLine: String,
    )

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
