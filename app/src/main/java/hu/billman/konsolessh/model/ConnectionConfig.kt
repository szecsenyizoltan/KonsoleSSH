package hu.billman.konsolessh.model

import java.util.UUID

data class ConnectionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
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
    enum class AuthType {
        PASSWORD, PRIVATE_KEY
    }

    fun displayName(): String = if (name.isNotBlank()) name else "$username@$host"

    /**
     * Whether the connection logs in with its private key; otherwise it uses
     * the password, or asks for one. The chosen [authType] decides. Older
     * versions saved both secrets whatever the choice, so a profile whose
     * chosen secret is empty while it holds a key still uses that key, as it
     * did before.
     */
    fun usesKey(): Boolean = privateKey.isNotBlank() && (authType == AuthType.PRIVATE_KEY || password.isBlank())

    fun hasJump(): Boolean = jumpConnectionId.isNotBlank() || jumpHost.isNotBlank()

    // Secrets must not reach logs or problem reports through string templates.
    override fun toString(): String =
        "ConnectionConfig(id=$id, name=$name, host=$host, port=$port, username=$username, " +
            "authType=$authType, jumpConnectionId=$jumpConnectionId, jumpHost=$jumpHost, " +
            "jumpPort=$jumpPort, jumpUsername=$jumpUsername, updatedAt=$updatedAt, secrets=<hidden>)"
}
