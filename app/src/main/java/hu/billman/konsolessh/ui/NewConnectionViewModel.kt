package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.model.ConnectionConfig
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
import kotlinx.coroutines.launch

/**
 * Az új/szerkesztett kapcsolat párbeszédablakának logikája: betöltés, mentés,
 * kapcsolatteszt és kulcsfeltöltés. A dialógus csak megjelenít; a műveletek
 * eredménye típusos, a hibák [SshError]-ként jönnek.
 *
 * A teszt és a feltöltés a ViewModel hatókörében fut, az eredménye és a host
 * key kérdései állapotként ([testResult], [uploadResult], [prompt]) jönnek,
 * így forgatás vagy a dialógus újraépülése után sem vesznek el.
 */
class NewConnectionViewModel(
    private val repository: ConnectionRepository,
    private val provisioner: SshKeyProvisioner,
) : ViewModel() {

    sealed class TestResult {
        data class Success(val serverVersion: String, val serverType: SshKeyProvisioner.ServerType) : TestResult()
        data class Failure(val error: SshError) : TestResult()
    }

    sealed class UploadResult {
        /** Sikeres feltöltés — a kulcspár használatra kész. */
        data class Success(
            val serverType: SshKeyProvisioner.ServerType,
            val privatePem: String,
            val publicLine: String,
        ) : UploadResult()

        /** A kulcsgenerálás bukott még a feltöltés előtt. */
        data class KeyGenerationFailure(val error: SshError) : UploadResult()

        /** A kulcs generálódott, de a feltöltés nem ment át — a publicLine kézi fallbackhez kell. */
        data class UploadFailure(val error: SshError, val publicLine: String) : UploadResult()
    }

    private val broker = PromptBroker()
    val prompt: StateFlow<PendingPrompt?> = broker.pending

    private val _busy = MutableStateFlow(false)

    /** Teszt vagy feltöltés fut. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _uploadResult = MutableStateFlow<UploadResult?>(null)
    val uploadResult: StateFlow<UploadResult?> = _uploadResult.asStateFlow()

    fun startTest(config: ConnectionConfig) = runOperation { _testResult.value = testConnection(config) }

    fun startKeyUpload(config: ConnectionConfig) = runOperation { _uploadResult.value = generateAndUploadKey(config) }

    /** A felület megjelenítette az eredményt. */
    fun onTestResultShown() {
        _testResult.value = null
    }

    fun onUploadResultShown() {
        _uploadResult.value = null
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    /** A szerkesztett kapcsolat eredeti állapota; a dialógusban nem látható mezőket ebből őrizzük meg. */
    var editConfig: ConnectionConfig? = null
        private set

    var selectedJumpId: String? = null

    fun answerPrompt(promptId: Long, answer: PromptAnswer) = broker.answer(promptId, answer)

    suspend fun loadForEdit(id: String): ConnectionConfig? =
        editConfig ?: repository.get(id).also { config ->
            editConfig = config
            if (selectedJumpId == null) selectedJumpId = config?.jumpConnectionId?.takeIf { it.isNotBlank() }
        }

    /** Mentett kapcsolatok jump hostnak, a szerkesztett kivételével. */
    suspend fun jumpOptions(): List<ConnectionConfig> = repository.load().filter { it.id != editConfig?.id }

    suspend fun mostUsedUsername(): String? =
        repository.load().groupBy { it.username }.maxByOrNull { it.value.size }?.key

    /**
     * Elmenti a kapcsolatot. A dialógusban nem szerkeszthető mezők (pl. a kulcs
     * passphrase-e, régi kézi jump-adatok) a szerkesztett konfigból megmaradnak.
     */
    suspend fun save(form: ConnectionConfig): Boolean {
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

    suspend fun testConnection(config: ConnectionConfig): TestResult = try {
        val jumps = JumpChain.resolve(config, repository.load())
        val outcome = provisioner.testConnection(config, jumps, broker)
        TestResult.Success(outcome.serverVersion, outcome.serverType)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult.Failure(classify(e))
    }

    /** Helyi RSA-3072 kulcspár + authorized_keys feltöltés; ha a generálás bukik, nincs feltöltés. */
    suspend fun generateAndUploadKey(config: ConnectionConfig): UploadResult {
        val generated = try {
            SshKeyGenerator.generateRsa3072(comment = "konsolessh:${config.username}@${config.host}")
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
}
