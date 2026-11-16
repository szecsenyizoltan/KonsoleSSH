package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.AuthType
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.session.PromptBroker
import hu.billman.konsolessh.ssh.JumpChain
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.SshErrorClassifier
import hu.billman.konsolessh.ssh.SshKeyGenerator
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The new / edit connection form. The whole form lives here as [FormState];
 * the screen only draws it and reports what the user did. Every rule of the
 * form is decided here: validation, when a connection test stops counting,
 * when key generation is allowed, what is visible.
 *
 * Tests and key uploads run in the ViewModel's scope, and their host key
 * questions arrive as [prompt], so they survive the screen being recreated.
 */
class NewConnectionViewModel(
    private val repository: ConnectionRepository,
    private val provisioner: SshKeyProvisioner,
) : ViewModel() {

    data class JumpOption(val id: String, val label: String)

    /** What a test checked; editing any of these makes the test stale. */
    data class Credentials(val host: String, val port: String, val username: String, val password: String)

    sealed interface Status {
        data object None : Status
        data object Testing : Status
        data object Uploading : Status
        data class TestSucceeded(val serverVersion: String) : Status
        data class TestFailed(val error: SshError) : Status
        data class Invalid(val reason: InvalidReason) : Status
        data class UploadSucceeded(val serverType: SshKeyProvisioner.ServerType) : Status
        data class UploadFailed(val error: SshError) : Status
        data object StoreUnreadable : Status
    }

    enum class InvalidReason { NEEDS_HOST_AND_USER, NEEDS_PRIVATE_KEY, NEEDS_PASSWORD }

    /** Short one-time notices. */
    sealed interface Message {
        data object HostRequired : Message
        data object UsernameRequired : Message
        data object StoreWriteFailed : Message
        data object NeedsTest : Message
        data class KeyGenerationFailed(val error: SshError) : Message
        data class KeyFileUnreadable(val reason: String) : Message

        /** The picked file looks like a public key, which cannot log in. */
        data object PublicKeyPicked : Message
    }

    /** Dialogs about a generated key; both show the public key line. */
    sealed interface KeyDialog {
        val publicLine: String

        data class Uploaded(override val publicLine: String) : KeyDialog
        data class ManualFallback(override val publicLine: String) : KeyDialog
    }

    data class FormState(
        val loaded: Boolean = false,
        val editing: Boolean = false,
        val name: String = "",
        val host: String = "",
        val port: String = DEFAULT_PORT.toString(),
        val username: String = "",
        val authType: AuthType = AuthType.PASSWORD,
        val password: String = "",
        val privateKey: String = "",
        val jumpId: String? = null,
        val jumpExpanded: Boolean = false,
        val jumpOptions: List<JumpOption> = emptyList(),
        val status: Status = Status.None,
        val busy: Boolean = false,
        val testedCredentials: Credentials? = null,
        /** The banner of the last successful password test; it picks the type of the key to upload. */
        val testedServerVersion: String = "",
    ) {
        val usesKey: Boolean get() = authType == AuthType.PRIVATE_KEY
        val hasKey: Boolean get() = privateKey.isNotBlank()

        /** Private addresses are usually reached through a jump host. */
        val isInternalHost: Boolean get() = PrivateAddress.isPrivate(host)
        val showJump: Boolean get() = isInternalHost || jumpId != null || jumpExpanded

        /** Key upload logs in with the password, so it needs a fresh password test. */
        val canGenerateKey: Boolean get() = !busy && testedCredentials != null && testedCredentials == credentials()

        fun credentials() = Credentials(host.trim(), port.trim(), username.trim(), password)
    }

    private val broker = PromptBroker()
    val prompt: StateFlow<PendingPrompt?> = broker.pending

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    private val _keyDialog = MutableStateFlow<KeyDialog?>(null)
    val keyDialog: StateFlow<KeyDialog?> = _keyDialog.asStateFlow()

    private val _savedId = MutableStateFlow<String?>(null)

    /** Id of the saved connection, once saving succeeded. */
    val savedId: StateFlow<String?> = _savedId.asStateFlow()

    /** The connection being edited, as stored: fields the form does not show are kept from it. */
    private var editConfig: ConnectionConfig? = null
    private var connectionId: String = UUID.randomUUID().toString()
    private var started = false

    /** Loads the form once per ViewModel; a recreated screen finds it filled. */
    fun start(editId: String?) {
        if (started) return
        started = true
        if (editId != null) connectionId = editId
        _form.update { it.copy(editing = editId != null) }
        viewModelScope.launch {
            try {
                val all = repository.load()
                val edited = editId?.let { id -> all.find { it.id == id } }
                editConfig = edited
                val options = all.filter { it.id != editId }.map { JumpOption(it.id, it.displayName()) }
                _form.update { form ->
                    val filled = if (edited != null) form.filledFrom(edited) else form.withUsualUsername(all)
                    // A jump host that no longer exists is dropped, not kept as a dangling id.
                    val jumpId = filled.jumpId?.takeIf { id -> options.any { it.id == id } }
                    filled.copy(jumpOptions = options, jumpId = jumpId)
                }
            } catch (e: ConnectionStoreException) {
                _form.update { it.copy(status = Status.StoreUnreadable) }
            } finally {
                _form.update { it.copy(loaded = true) }
            }
        }
    }

    private fun FormState.filledFrom(config: ConnectionConfig) = copy(
        name = config.name,
        host = config.host,
        port = config.port.toString(),
        username = config.username,
        authType = config.authType,
        password = config.password,
        privateKey = config.privateKey,
        jumpId = config.jumpConnectionId.takeIf { it.isNotBlank() },
    )

    private fun FormState.withUsualUsername(all: List<ConnectionConfig>): FormState {
        if (username.isNotBlank()) return this
        val usual = all.groupBy { it.username }.maxByOrNull { it.value.size }?.key.orEmpty()
        return copy(username = usual)
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    fun setName(value: String) = _form.update { it.copy(name = value) }

    fun setHost(value: String) = editCredentials { it.copy(host = value) }

    fun setPort(value: String) = editCredentials { it.copy(port = value) }

    fun setUsername(value: String) = editCredentials { it.copy(username = value) }

    fun setPassword(value: String) = editCredentials { it.copy(password = value) }

    fun setAuthType(value: AuthType) = _form.update { it.copy(authType = value) }

    fun setPrivateKey(value: String) = _form.update { it.copy(privateKey = value) }

    fun keyFileUnreadable(reason: String) {
        _message.value = Message.KeyFileUnreadable(reason)
    }

    fun publicKeyPicked() {
        _message.value = Message.PublicKeyPicked
    }

    fun selectJump(id: String?) = _form.update { it.copy(jumpId = id) }

    fun expandJump() = _form.update { it.copy(jumpExpanded = true) }

    /** A change to what was tested drops the test result. */
    private fun editCredentials(change: (FormState) -> FormState) = _form.update { old ->
        val new = change(old)
        if (new.testedCredentials != null && new.credentials() != new.testedCredentials) {
            new.copy(testedCredentials = null, status = Status.None)
        } else {
            new
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun test() {
        val form = _form.value
        if (form.busy) return
        val config = form.toConfig()
        val invalid = when {
            config.host.isEmpty() || config.username.isEmpty() -> InvalidReason.NEEDS_HOST_AND_USER
            form.usesKey && config.privateKey.isEmpty() -> InvalidReason.NEEDS_PRIVATE_KEY
            !form.usesKey && config.password.isEmpty() -> InvalidReason.NEEDS_PASSWORD
            else -> null
        }
        if (invalid != null) {
            _form.update { it.copy(status = Status.Invalid(invalid), testedCredentials = null) }
            return
        }
        // Only the credentials of the selected mode are tested.
        val probe = if (form.usesKey) config.copy(password = "") else config.copy(privateKey = "")
        val tested = form.credentials()
        val passwordTest = !form.usesKey
        runOperation(Status.Testing) {
            val status = testConnection(probe)
            _form.update {
                when (status) {
                    is Status.TestSucceeded -> it.copy(
                        status = status,
                        testedCredentials = tested.takeIf { passwordTest },
                        testedServerVersion = status.serverVersion,
                    )
                    else -> it.copy(status = status, testedCredentials = null)
                }
            }
        }
    }

    fun generateKey() {
        val form = _form.value
        if (!form.canGenerateKey) {
            if (!form.busy) _message.value = Message.NeedsTest
            return
        }
        val config = form.toConfig().copy(authType = AuthType.PASSWORD, privateKey = "")
        val previousStatus = form.status
        val tested = form.testedCredentials
        runOperation(Status.Uploading) {
            when (val result = generateAndUploadKey(config, form.testedServerVersion)) {
                is UploadResult.KeyGenerationFailure -> {
                    _form.update { it.copy(status = previousStatus) }
                    _message.value = Message.KeyGenerationFailed(result.error)
                }
                is UploadResult.Success -> {
                    // The password is replaced by the key, so the earlier test no longer applies.
                    _form.update {
                        it.copy(
                            authType = AuthType.PRIVATE_KEY,
                            privateKey = result.privatePem,
                            password = "",
                            status = Status.UploadSucceeded(result.serverType),
                            testedCredentials = null,
                        )
                    }
                    _keyDialog.value = KeyDialog.Uploaded(result.publicLine)
                }
                is UploadResult.UploadFailure -> {
                    // The test still holds, so the user can retry.
                    _form.update { it.copy(status = Status.UploadFailed(result.error), testedCredentials = tested) }
                    _keyDialog.value = KeyDialog.ManualFallback(result.publicLine)
                }
            }
        }
    }

    fun save() {
        val config = _form.value.toConfig()
        when {
            config.host.isEmpty() -> _message.value = Message.HostRequired
            config.username.isEmpty() -> _message.value = Message.UsernameRequired
            else -> viewModelScope.launch {
                if (store(config)) _savedId.value = config.id else _message.value = Message.StoreWriteFailed
            }
        }
    }

    fun answerPrompt(promptId: Long, answer: PromptAnswer) = broker.answer(promptId, answer)

    fun onMessageShown() {
        _message.value = null
    }

    fun onKeyDialogShown() {
        _keyDialog.value = null
    }

    private fun runOperation(running: Status, block: suspend () -> Unit) {
        _form.update { it.copy(busy = true, status = running) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _form.update { it.copy(busy = false) }
            }
        }
    }

    private fun FormState.toConfig() = ConnectionConfig(
        id = connectionId,
        name = name.trim(),
        host = host.trim(),
        port = port.trim().toIntOrNull()?.coerceIn(1, MAX_PORT) ?: DEFAULT_PORT,
        username = username.trim(),
        authType = authType,
        password = password,
        privateKey = privateKey.trim(),
        jumpConnectionId = jumpId.orEmpty(),
    )

    /**
     * Fields the form does not edit (the key passphrase, an old manual jump
     * host) are kept from the stored connection.
     */
    private suspend fun store(form: ConnectionConfig): Boolean {
        val config = editConfig?.let { old ->
            form.copy(
                privateKeyPassphrase = old.privateKeyPassphrase,
                jumpHost = old.jumpHost.takeIf { form.jumpConnectionId.isBlank() }.orEmpty(),
                jumpPort = old.jumpPort,
                jumpUsername = old.jumpUsername,
                jumpPassword = old.jumpPassword,
            )
        } ?: form
        return try {
            repository.saveOne(config)
            true
        } catch (e: ConnectionStoreException) {
            false
        }
    }

    private suspend fun testConnection(config: ConnectionConfig): Status = try {
        val jumps = JumpChain.resolve(config, repository.load())
        val outcome = provisioner.testConnection(config, jumps, broker)
        Status.TestSucceeded(outcome.serverVersion)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Status.TestFailed(classify(e))
    }

    private sealed interface UploadResult {
        data class Success(
            val serverType: SshKeyProvisioner.ServerType,
            val privatePem: String,
            val publicLine: String,
        ) : UploadResult

        /** Generating the key failed; nothing was uploaded. */
        data class KeyGenerationFailure(val error: SshError) : UploadResult

        /** The key exists but the upload failed; the public line is offered for a manual install. */
        data class UploadFailure(val error: SshError, val publicLine: String) : UploadResult
    }

    /** A local key pair for the tested server, its public half appended to authorized_keys. */
    private suspend fun generateAndUploadKey(config: ConnectionConfig, serverVersion: String): UploadResult {
        val generated = try {
            SshKeyGenerator.generateFor(serverVersion, comment = "konsolessh:${config.username}@${config.host}")
        } catch (e: com.jcraft.jsch.JSchException) {
            return UploadResult.KeyGenerationFailure(SshErrorClassifier.classify(e))
        }
        return try {
            val jumps = JumpChain.resolve(config, repository.load())
            val serverType = provisioner.uploadPublicKey(config, jumps, generated.publicLine, broker)
            UploadResult.Success(serverType, generated.privatePem, generated.publicLine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadResult.UploadFailure(classify(e), generated.publicLine)
        }
    }

    private fun classify(e: Exception): SshError =
        if (e is ConnectionStoreException) SshError.StoreUnreadable else SshErrorClassifier.classify(e)

    override fun onCleared() {
        broker.cancel()
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val MAX_PORT = 65535
    }
}
