package hu.szecsenyi.konsolessh.model

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
    val jumpConnectionId: String = "",  // reference to a saved connection (preferred)
    val jumpHost: String = "",          // manual entry (used when jumpConnectionId is blank)
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = ""
) {
    enum class AuthType {
        PASSWORD, PRIVATE_KEY
    }

    fun displayName(): String = if (name.isNotBlank()) name else "$username@$host"

    fun hasJump(): Boolean = jumpConnectionId.isNotBlank() || jumpHost.isNotBlank()
}
