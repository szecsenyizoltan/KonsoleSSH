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
        authType = when (authType) {
            AuthType.PASSWORD -> ConnectionConfig.AuthType.PASSWORD
            AuthType.PRIVATE_KEY -> ConnectionConfig.AuthType.PRIVATE_KEY
        },
        password = password,
        privateKey = privateKey,
        privateKeyPassphrase = privateKeyPassphrase,
        jumpConnectionId = jumpConnectionId,
        jumpHost = jumpHost,
        jumpPort = jumpPort,
        jumpUsername = jumpUsername,
        jumpPassword = jumpPassword,
        updatedAt = updatedAt,
    )

    companion object {
        fun of(config: ConnectionConfig) = StoredConnection(
            id = config.id,
            name = config.name,
            host = config.host,
            port = config.port,
            username = config.username,
            authType = when (config.authType) {
                ConnectionConfig.AuthType.PASSWORD -> AuthType.PASSWORD
                ConnectionConfig.AuthType.PRIVATE_KEY -> AuthType.PRIVATE_KEY
            },
            password = config.password,
            privateKey = config.privateKey,
            privateKeyPassphrase = config.privateKeyPassphrase,
            jumpConnectionId = config.jumpConnectionId,
            jumpHost = config.jumpHost,
            jumpPort = config.jumpPort,
            jumpUsername = config.jumpUsername,
            jumpPassword = config.jumpPassword,
            updatedAt = config.updatedAt,
        )
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
