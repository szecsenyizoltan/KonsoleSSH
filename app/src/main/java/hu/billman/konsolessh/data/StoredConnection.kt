package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A saved connection as the store and the sync file hold it. The field
 * names, their order and the enum values are the format that every version
 * since 1.x reads and writes (see [ConnectionJson]), so this class changes
 * only together with that format. The app works with [ConnectionConfig];
 * keeping the two apart lets the model change without touching stored data.
 */
@Serializable
internal data class StoredConnection(
    val id: String,
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val jumpConnectionId: String = "",
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUsername: String = "",
    val jumpPassword: String = "",
    val updatedAt: Long = 0L,
) {
    @Serializable
    enum class AuthType { PASSWORD, PRIVATE_KEY }

    fun toConfig() = ConnectionConfig(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        auth = auth(),
        jump = jump(),
        updatedAt = updatedAt,
    )

    /**
     * The chosen method decides. Older versions saved both secrets
     * whatever the choice, so a profile whose chosen secret is empty while
     * it holds a key keeps using that key, as it did then; the unused
     * secret is dropped the next time the profile is written.
     */
    private fun auth(): ConnectionConfig.Auth {
        val usesKey = privateKey.isNotBlank() && (authType == AuthType.PRIVATE_KEY || password.isBlank())
        return if (usesKey) {
            ConnectionConfig.Auth.PrivateKey(privateKey, privateKeyPassphrase)
        } else {
            ConnectionConfig.Auth.Password(password)
        }
    }

    // A saved jump host wins over leftover manual fields, as it always has.
    private fun jump(): ConnectionConfig.Jump = when {
        jumpConnectionId.isNotBlank() -> ConnectionConfig.Jump.Saved(jumpConnectionId)
        jumpHost.isNotBlank() -> ConnectionConfig.Jump.Manual(jumpHost, jumpPort, jumpUsername, jumpPassword)
        else -> ConnectionConfig.Jump.None
    }

    companion object {
        fun of(config: ConnectionConfig): StoredConnection {
            val password = config.auth as? ConnectionConfig.Auth.Password
            val key = config.auth as? ConnectionConfig.Auth.PrivateKey
            val manual = config.jump as? ConnectionConfig.Jump.Manual
            return StoredConnection(
                id = config.id,
                name = config.name,
                host = config.host,
                port = config.port,
                username = config.username,
                authType = if (key != null) AuthType.PRIVATE_KEY else AuthType.PASSWORD,
                password = password?.password.orEmpty(),
                privateKey = key?.pem.orEmpty(),
                privateKeyPassphrase = key?.passphrase.orEmpty(),
                jumpConnectionId = (config.jump as? ConnectionConfig.Jump.Saved)?.connectionId.orEmpty(),
                jumpHost = manual?.host.orEmpty(),
                jumpPort = manual?.port ?: DEFAULT_PORT,
                jumpUsername = manual?.username.orEmpty(),
                jumpPassword = manual?.password.orEmpty(),
                updatedAt = config.updatedAt,
            )
        }

        private const val DEFAULT_PORT = 22
    }
}

/** Reads and writes a [ConnectionConfig] in the [StoredConnection] format. */
internal object StoredConnectionSerializer : KSerializer<ConnectionConfig> {
    override val descriptor: SerialDescriptor = StoredConnection.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ConnectionConfig) =
        encoder.encodeSerializableValue(StoredConnection.serializer(), StoredConnection.of(value))

    override fun deserialize(decoder: Decoder): ConnectionConfig =
        decoder.decodeSerializableValue(StoredConnection.serializer()).toConfig()
}
