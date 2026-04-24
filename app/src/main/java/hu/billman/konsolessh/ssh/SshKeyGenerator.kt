package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/**
 * RSA keypair generálás az authorized_keys-alapú SSH autentikációhoz.
 * RSA-ra esett a választás, mert minden célplatform (Linux, Mikrotik, Dropbear)
 * kezeli; az ED25519 külön EdDSA-provider jelenlétét feltételezné az Android
 * runtime-on, amit nem tudunk garantálni.
 *
 * Pure logic, Android-függőségek nélkül — unit-tesztelhető JVM-en.
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
