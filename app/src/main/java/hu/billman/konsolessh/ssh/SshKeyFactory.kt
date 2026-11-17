package hu.billman.konsolessh.ssh

/** A generated key pair: the private key for the store, the public line for authorized_keys. */
data class GeneratedKey(
    val privatePem: String,
    val publicLine: String,
)

/**
 * Creates the key pair that the key upload installs. An interface so that
 * callers outside the `ssh` package do not depend on JSch or Bouncy Castle,
 * and their tests do not spend seconds generating real RSA keys.
 */
fun interface SshKeyFactory {
    /** A key type the server that sent [serverVersion] accepts; throws [KeyGenerationException]. */
    fun generateFor(serverVersion: String, comment: String): GeneratedKey
}

/** No key pair could be generated; nothing was uploaded. */
class KeyGenerationException(cause: Throwable) : Exception(cause.message, cause)
