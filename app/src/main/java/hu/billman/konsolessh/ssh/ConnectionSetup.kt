package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CancellationException

/**
 * The server-side steps of setting up a connection: testing that it works,
 * and installing a newly generated key with the tested password. Both go
 * through the connection's jump chain, looked up among all saved
 * connections, and report a failure as an [SshError], so the caller deals
 * with neither JSch, the store, nor key generation.
 */
class ConnectionSetup(
    private val repository: ConnectionRepository,
    private val provisioner: SshKeyProvisioner,
    private val keyFactory: SshKeyFactory,
) {

    sealed interface TestResult {
        data class Succeeded(val serverVersion: String) : TestResult
        data class Failed(val error: SshError) : TestResult
    }

    sealed interface KeyResult {
        data class Installed(
            val serverType: SshKeyProvisioner.ServerType,
            val privatePem: String,
            val publicLine: String,
        ) : KeyResult

        /** Generating the key failed; nothing was uploaded. */
        data class GenerationFailed(val error: SshError) : KeyResult

        /** The key exists but the upload failed; the public line is offered for a manual install. */
        data class UploadFailed(val error: SshError, val publicLine: String) : KeyResult
    }

    suspend fun test(config: ConnectionConfig, prompter: SessionPrompter): TestResult = try {
        val jumps = JumpChain.resolve(config, repository)
        TestResult.Succeeded(provisioner.testConnection(config, jumps, prompter).serverVersion)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TestResult.Failed(SshErrorClassifier.classify(e))
    }

    /**
     * Generates a key pair of a type the server accepts ([serverVersion] is
     * its banner from the test, D15) and appends the public half to the
     * server's authorized keys. [config] must log in with a password.
     */
    suspend fun installNewKey(config: ConnectionConfig, serverVersion: String, prompter: SessionPrompter): KeyResult {
        val generated = try {
            keyFactory.generateFor(serverVersion, comment = "konsolessh:${config.username}@${config.host}")
        } catch (e: KeyGenerationException) {
            return KeyResult.GenerationFailed(SshErrorClassifier.classify(e))
        }
        return try {
            val jumps = JumpChain.resolve(config, repository)
            val serverType = provisioner.uploadPublicKey(config, jumps, generated.publicLine, prompter)
            KeyResult.Installed(serverType, generated.privatePem, generated.publicLine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeyResult.UploadFailed(SshErrorClassifier.classify(e), generated.publicLine)
        }
    }
}
