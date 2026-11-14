package hu.billman.konsolessh.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.HostKeyStore
import hu.billman.konsolessh.sync.DriveAuthorizer
import hu.billman.konsolessh.sync.MemoryDrive
import hu.billman.konsolessh.sync.MemoryKeys
import hu.billman.konsolessh.sync.SyncManager
import hu.billman.konsolessh.ui.SyncViewModel.Problem
import hu.billman.konsolessh.ui.SyncViewModel.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncViewModelTest {

    private val drive = MemoryDrive()
    private val repo = FakeConnectionRepository()
    private val dismissed = MutableStateFlow(false)
    private val settings = object : SettingsRepository {
        override val fontSizeSp = MutableStateFlow(0f)
        override suspend fun setFontSizeSp(value: Float) = Unit
        override val syncOfferDismissed = dismissed
        override suspend fun dismissSyncOffer() {
            dismissed.value = true
        }
    }
    private val manager = SyncManager(
        connections = repo,
        hostKeys = HostKeyStore(File(Files.createTempDirectory("vm").toFile(), "known_hosts")),
        keys = MemoryKeys(),
        remote = drive,
        scope = CoroutineScope(Dispatchers.Unconfined),
        iterations = 1_000,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SyncViewModel(manager, settings, repo)

    private fun SyncViewModel.awaitStep(step: Step) = runBlocking {
        withTimeout(TIMEOUT_MS) { state.first { it.step == step } }
    }

    @Test
    fun `an empty Drive leads to choosing a new passphrase, then sync is on`() {
        val vm = vm()
        vm.connect()
        vm.awaitStep(Step.CREATE)

        vm.enable("short", "short")
        assertEquals(Problem.TOO_SHORT, vm.state.value.problem)
        vm.enable("long enough", "different!")
        assertEquals(Problem.MISMATCH, vm.state.value.problem)

        vm.enable("long enough", "long enough")
        vm.awaitStep(Step.ON)
        assertNotNull(drive.content)
    }

    @Test
    fun `an existing backup asks for its passphrase, and a wrong one is reported`() {
        runBlocking { manager.enable("the right one".toCharArray()) }
        runBlocking { manager.disable() }
        val vm = vm()
        vm.connect()
        vm.awaitStep(Step.RESTORE)

        vm.enable("not the right one", null)
        vm.awaitStep(Step.RESTORE)
        runBlocking { withTimeout(TIMEOUT_MS) { vm.state.first { it.problem == Problem.WRONG_PASSPHRASE } } }

        vm.enable("the right one", null)
        vm.awaitStep(Step.ON)
    }

    @Test
    fun `missing Google access opens the consent screen and retries after it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        drive.failure = DriveAuthorizer.ConsentNeededException(intent)
        val vm = vm()
        vm.connect()
        assertEquals(intent, vm.consent.value)
        assertEquals(Problem.CONSENT, vm.state.value.problem)

        vm.onConsentShown()
        drive.failure = null
        vm.onConsentResult(granted = true)
        vm.awaitStep(Step.CREATE)
    }

    @Test
    fun `the restore offer shows on an empty first start, and not again once declined`() {
        val vm = vm()
        assertTrue(vm.offerRestore.value)
        vm.dismissOffer()
        assertFalse(vm.offerRestore.value)
    }

    @Test
    fun `no restore offer when connections are already saved`() {
        runBlocking { repo.saveOne(ConnectionConfig(id = "a")) }
        assertFalse(vm().offerRestore.value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
