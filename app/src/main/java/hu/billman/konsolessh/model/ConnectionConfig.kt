package hu.billman.konsolessh.model

import java.util.UUID

data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: Auth = Auth.Password(""),
    // Jump host (SSH -J)
    val jumpConnectionId: String = "", // reference to a saved connection (preferred)
    val jumpHost: String = "", // manual entry (used when jumpConnectionId is blank)
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = "",
    // When this connection last changed, in epoch milliseconds; 0 before 2.2.0.
    // Sync between devices keeps the newer copy.
    val updatedAt: Long = 0L,
) {
    /** How the connection logs in; only one method's secrets are kept. */
    sealed interface Auth {
        /** A blank password is asked from the user when connecting. */
        data class Password(val password: String) : Auth {
            override fun toString() = "Password(<hidden>)"
        }

        data class PrivateKey(val pem: String, val passphrase: String = "") : Auth {
            override fun toString() = "PrivateKey(<hidden>)"
        }
    }

    fun displayName(): String = if (name.isNotBlank()) name else "$username@$host"

    fun hasJump(): Boolean = jumpConnectionId.isNotBlank() || jumpHost.isNotBlank()

    // Secrets must not reach logs or problem reports through string templates.
    override fun toString(): String =
        "ConnectionConfig(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "auth=$auth, jumpConnectionId=$jumpConnectionId, jumpHost=$jumpHost, " +
            "jumpPort=$jumpPort, jumpUsername=$jumpUsername, updatedAt=$updatedAt, secrets=<hidden>)"
}
