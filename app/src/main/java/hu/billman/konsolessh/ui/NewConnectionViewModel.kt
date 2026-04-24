package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.SshKeyGenerator
import hu.billman.konsolessh.ssh.SshKeyProvisioner

/**
 * A NewConnectionDialog üzleti lépéseit (jelszavas csatlakozás-teszt,
 * kulcsgenerálás + feltöltés) pure logicként tartja. A dialog csak UI-
 * koordinátor: hívja a suspend-függvényeket és a visszaadott sealed
 * eredmény alapján rendereli a felhasználói visszajelzést.
 *
 * Miért érdemes ViewModel: rotáció / configChange / Fragment-recreate
 * esetén a futó művelet nem vész el (a ViewModelScope túléli), a dialog
 * új példánya csatlakozhat a már eredményhez. Ebben a lépésben még
 * csak a logikát emelem ki; a rotációs-resilience Lépés 5-ben jön.
 */
class NewConnectionViewModel : ViewModel() {

    sealed class TestResult {
        data class Success(
            val serverVersion: String,
            val serverType: SshKeyProvisioner.ServerType,
        ) : TestResult()

        data class Failure(val errorMessage: String) : TestResult()
    }

    sealed class UploadResult {
        /** Sikeres feltöltés — a kulcspár használatra kész. */
        data class Success(
            val serverType: SshKeyProvisioner.ServerType,
            val privatePem: String,
            val publicLine: String,
        ) : UploadResult()

        /** A kulcsgenerálás bukott még a feltöltés előtt. */
        data class KeyGenerationFailure(val errorMessage: String) : UploadResult()

        /** A kulcs generálódott, de a feltöltés nem ment át — a publicLine hasznos manuális fallback-hoz. */
        data class UploadFailure(val errorMessage: String, val publicLine: String) : UploadResult()
    }

    /**
     * Jelszóval csatlakozás-teszt, szerver-verzió és típus visszajelzéssel.
     * Kivételt nem dob — a hibát [TestResult.Failure] rögzíti.
     */
    suspend fun testConnection(config: ConnectionConfig): TestResult = try {
        val outcome = SshKeyProvisioner.testPasswordConnection(config)
        TestResult.Success(outcome.serverVersion, outcome.serverType)
    } catch (e: Exception) {
        TestResult.Failure(friendlyShortError(e))
    }

    /**
     * Helyi RSA-3072 kulcspár generálása + authorized_keys feltöltés a
     * szerverre. Ha a kulcsgenerálás bukik, a feltöltésre nem kerül sor.
     */
    suspend fun generateAndUploadKey(config: ConnectionConfig): UploadResult {
        val generated = try {
            SshKeyGenerator.generateRsa3072(comment = "konsolessh:${config.username}@${config.host}")
        } catch (e: Exception) {
            return UploadResult.KeyGenerationFailure(friendlyShortError(e))
        }
        return try {
            val serverType = SshKeyProvisioner.uploadPublicKey(config, generated.publicLine)
            UploadResult.Success(serverType, generated.privatePem, generated.publicLine)
        } catch (e: Exception) {
            UploadResult.UploadFailure(friendlyShortError(e), generated.publicLine)
        }
    }

    companion object {
        /**
         * Technical exception-üzenetből emberi stringet képez: elveszi a
         * stack-elő lib-prefixeket (`session.connect: …`, `com.jcraft.jsch.…:`)
         * és maximum 200 karakteren megvágja.
         */
        fun friendlyShortError(err: Throwable): String {
            val msg = err.message ?: err.javaClass.simpleName
            return msg.replace(Regex("^session\\.connect:\\s*"), "")
                .replace(Regex("^[a-zA-Z]+(\\.[a-zA-Z]+)+:\\s*"), "")
                .take(200)
        }
    }
}
