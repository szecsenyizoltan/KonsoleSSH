package hu.billman.konsolessh.model

import java.util.UUID

data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: Auth = Auth.Password(""),
    val jump: Jump = Jump.None,
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

    /** The jump host in front of this connection (ssh -J). */
    sealed interface Jump {
        data object None : Jump

        /** Another saved connection, which may have a jump host of its own. */
        data class Saved(val connectionId: String) : Jump

        /**
         * A jump host typed in by hand in older versions. The form no longer
         * offers it, but keeps it while the user picks no saved jump host.
         */
        data class Manual(val host: String, val port: Int = 22, val username: String, val password: String) : Jump {
            override fun toString() = "Manual(host=$host, port=$port, username=$username, password=<hidden>)"
        }
    }

    fun displayName(): String = if (name.isNotBlank()) name else "$username@$host"

    // Secrets must not reach logs or problem reports through string templates.
    override fun toString(): String =
        "ConnectionConfig(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "auth=$auth, jump=$jump, updatedAt=$updatedAt)"
}
