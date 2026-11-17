package hu.billman.konsolessh.ui.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.Auth
import hu.billman.konsolessh.model.ConnectionConfig.Jump
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.session.PromptBroker
import hu.billman.konsolessh.ssh.ConnectionSetup
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    private val setup: ConnectionSetup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class JumpOption(val id: String, val label: String)

    /** What a test checked; editing any of these makes the test stale. */
    data class Credentials(val host: String, val port: String, val username: String, val password: String)

    /**
     * A successful password test: what it checked and the banner of the
     * server it reached, which picks the type of the key to upload.
     */
    data class PasswordTest(val credentials: Credentials, val serverVersion: String)

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

    enum class KeyFileProblem { UNREADABLE, TOO_LARGE }

    enum class InvalidReason { NEEDS_HOST_AND_USER, NEEDS_PRIVATE_KEY, NEEDS_PASSWORD }

    /** Short one-time notices. */
    sealed interface Message {
        data object HostRequired : Message
        data object UsernameRequired : Message
        data object StoreWriteFailed : Message
        data object NeedsTest : Message
        data class KeyGenerationFailed(val error: SshError) : Message
        data class KeyFileUnreadable(val problem: KeyFileProblem) : Message

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
        val authType: AuthMethod = AuthMethod.PASSWORD,
        val password: String = "",
        val privateKey: String = "",
        val jumpId: String? = null,
        val jumpExpanded: Boolean = false,
        val jumpOptions: List<JumpOption> = emptyList(),
        val status: Status = Status.None,
        val busy: Boolean = false,
        val passwordTest: PasswordTest? = null,
    ) {
        val usesKey: Boolean get() = authType == AuthMethod.PRIVATE_KEY
        val hasKey: Boolean get() = privateKey.isNotBlank()

        /** Private addresses are usually reached through a jump host. */
        val isInternalHost: Boolean get() = PrivateAddress.isPrivate(host)
        val showJump: Boolean get() = isInternalHost || jumpId != null || jumpExpanded

        /** Key upload logs in with the password, so it needs a fresh password test. */
        val canGenerateKey: Boolean get() = !busy && passwordTest?.credentials == credentials()

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
        authType = if (config.auth is Auth.PrivateKey) AuthMethod.PRIVATE_KEY else AuthMethod.PASSWORD,
        password = (config.auth as? Auth.Password)?.password.orEmpty(),
        privateKey = (config.auth as? Auth.PrivateKey)?.pem.orEmpty(),
        jumpId = (config.jump as? Jump.Saved)?.connectionId,
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

    fun setAuthType(value: AuthMethod) = _form.update { it.copy(authType = value) }

    fun setPrivateKey(value: String) = _form.update { it.copy(privateKey = value) }

    /**
     * Reads a picked key file into the form. [open] runs on an I/O thread,
     * since a document provider such as a cloud drive may block; it must not
     * hold on to an activity. [path] only serves to recognise a public key.
     */
    fun loadKeyFile(path: String, open: () -> InputStream?) {
        if (path.lowercase().endsWith(".pub")) _message.value = Message.PublicKeyPicked
        viewModelScope.launch {
            val bytes = try {
                withContext(ioDispatcher) { open()?.use { it.readAtMost(MAX_KEY_FILE_BYTES + 1) } }
            } catch (e: IOException) {
                _message.value = Message.KeyFileUnreadable(KeyFileProblem.UNREADABLE)
                return@launch
            } catch (e: SecurityException) {
                _message.value = Message.KeyFileUnreadable(KeyFileProblem.UNREADABLE)
                return@launch
            }
            when {
                bytes == null -> Unit
                bytes.size > MAX_KEY_FILE_BYTES -> _message.value = Message.KeyFileUnreadable(KeyFileProblem.TOO_LARGE)
                else -> setPrivateKey(String(bytes, Charsets.UTF_8))
            }
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_CHUNK)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun selectJump(id: String?) = _form.update { it.copy(jumpId = id) }

    fun expandJump() = _form.update { it.copy(jumpExpanded = true) }

    /** A change to what was tested drops the test result. */
    private fun editCredentials(change: (FormState) -> FormState) = _form.update { old ->
        val new = change(old)
        if (new.passwordTest != null && new.credentials() != new.passwordTest.credentials) {
            new.copy(passwordTest = null, status = Status.None)
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
            form.usesKey && form.privateKey.isBlank() -> InvalidReason.NEEDS_PRIVATE_KEY
            !form.usesKey && form.password.isEmpty() -> InvalidReason.NEEDS_PASSWORD
            else -> null
        }
        if (invalid != null) {
            _form.update { it.copy(status = Status.Invalid(invalid), passwordTest = null) }
            return
        }
        val tested = form.credentials()
        val isPasswordTest = !form.usesKey
        runOperation(Status.Testing) {
            val status = when (val result = setup.test(config, broker)) {
                is ConnectionSetup.TestResult.Succeeded -> Status.TestSucceeded(result.serverVersion)
                is ConnectionSetup.TestResult.Failed -> Status.TestFailed(result.error)
            }
            _form.update {
                when (status) {
                    is Status.TestSucceeded -> it.copy(
                        status = status,
                        passwordTest = PasswordTest(tested, status.serverVersion).takeIf { isPasswordTest },
                    )
                    else -> it.copy(status = status, passwordTest = null)
                }
            }
        }
    }

    fun generateKey() {
        val form = _form.value
        val tested = form.passwordTest
        if (tested == null || !form.canGenerateKey) {
            if (!form.busy) _message.value = Message.NeedsTest
            return
        }
        // The upload logs in with the tested password, even if the user has switched to key mode since.
        val config = form.copy(authType = AuthMethod.PASSWORD).toConfig()
        val previousStatus = form.status
        runOperation(Status.Uploading) {
            when (val result = setup.installNewKey(config, tested.serverVersion, broker)) {
                is ConnectionSetup.KeyResult.GenerationFailed -> {
                    _form.update { it.copy(status = previousStatus) }
                    _message.value = Message.KeyGenerationFailed(result.error)
                }
                is ConnectionSetup.KeyResult.Installed -> {
                    // The password is replaced by the key, so the earlier test no longer applies.
                    _form.update {
                        it.copy(
                            authType = AuthMethod.PRIVATE_KEY,
                            privateKey = result.privatePem,
                            password = "",
                            status = Status.UploadSucceeded(result.serverType),
                            passwordTest = null,
                        )
                    }
                    _keyDialog.value = KeyDialog.Uploaded(result.publicLine)
                }
                is ConnectionSetup.KeyResult.UploadFailed -> {
                    // The test still holds, so the user can retry.
                    _form.update { it.copy(status = Status.UploadFailed(result.error), passwordTest = tested) }
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
        // Only the chosen method's secret is saved.
        auth = if (usesKey) Auth.PrivateKey(privateKey.trim()) else Auth.Password(password),
        jump = jumpId?.let(Jump::Saved) ?: Jump.None,
    )

    /**
     * Fields the form does not edit (the key passphrase, an old manual jump
     * host) are kept from the stored connection.
     */
    private suspend fun store(form: ConnectionConfig): Boolean {
        val config = editConfig?.let { old ->
            val passphrase = (old.auth as? Auth.PrivateKey)?.passphrase.orEmpty()
            form.copy(
                auth = (form.auth as? Auth.PrivateKey)?.copy(passphrase = passphrase) ?: form.auth,
                jump = old.jump.takeIf { it is Jump.Manual && form.jump == Jump.None } ?: form.jump,
            )
        } ?: form
        return try {
            repository.saveOne(config)
            true
        } catch (e: ConnectionStoreException) {
            false
        }
    }

    override fun onCleared() {
        broker.cancel()
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val MAX_PORT = 65535

        /** A private key is a few KB; a larger file is certainly not one. */
        const val MAX_KEY_FILE_BYTES = 64 * 1024
        const val READ_CHUNK = 8192
    }
}
