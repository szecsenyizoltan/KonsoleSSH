package hu.billman.konsolessh.ui

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.diagnostics.ProblemReports
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.SessionsState
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ui.MainViewModel.Confirm
import hu.billman.konsolessh.ui.MainViewModel.Message
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val state = MutableStateFlow(SessionsState())
    private val sessions = mockk<SessionManager>(relaxed = true).also { every { it.state } returns state }
    private val settings = object : SettingsRepository {
        override val fontSizeSp = flowOf(0f)
        override suspend fun setFontSizeSp(value: Float) = Unit
    }
    private var latestReport: File? = null
    private val reports = object : ProblemReports {
        override suspend fun collectNew(): File? = null
        override suspend fun latest(): File? = latestReport
        override suspend fun markSeen() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MainViewModel(
        sessions,
        FakeConnectionRepository(),
        settings,
        reports,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun tab(id: String, status: ConnectionStatus, prompt: PendingPrompt? = null, error: SshError? = null) =
        TabState(id, "c-$id", "Tab $id", "Tab $id", "h", status, error, prompt)

    // ── Closing ───────────────────────────────────────────────────────────────

    @Test
    fun `closing a connected tab asks first, any other tab closes at once`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED), tab("b", ConnectionStatus.DISCONNECTED)))
        val vm = vm()

        vm.requestCloseTab("b")
        verify { sessions.closeTab("b") }
        assertNull(vm.confirm.value)

        vm.requestCloseTab("a")
        assertEquals(Confirm.CloseTab("a", "Tab a"), vm.confirm.value)
        verify(exactly = 0) { sessions.closeTab("a") }

        vm.confirmed(vm.confirm.value!!)
        verify { sessions.closeTab("a") }
    }

    @Test
    fun `exiting with live connections asks first and names how many`() {
        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.CONNECTED), tab("b", ConnectionStatus.CONNECTED), tab("c", ConnectionStatus.CONNECTING)),
        )
        val vm = vm()

        vm.requestCloseApp()
        assertEquals(Confirm.CloseApp(2), vm.confirm.value)
        assertFalse(vm.exit.value)

        vm.confirmed(vm.confirm.value!!)
        verify { sessions.closeAll() }
        assertTrue(vm.exit.value)
    }

    @Test
    fun `exiting without connections needs no confirmation`() {
        val vm = vm()
        vm.requestCloseApp()
        assertNull(vm.confirm.value)
        assertTrue(vm.exit.value)
    }

    @Test
    fun `a blank tab name keeps the current one`() {
        val vm = vm()
        vm.renameTab("a", "   ")
        verify(exactly = 0) { sessions.renameTab(any(), any()) }
        vm.renameTab("a", "  prod  ")
        verify { sessions.renameTab("a", "prod") }
    }

    // ── Derived state ─────────────────────────────────────────────────────────

    @Test
    fun `one prompt is shown at a time, the first tab's`() {
        val vm = vm()
        val second = PendingPrompt.Password(2, "b.example")
        val first = PendingPrompt.Password(1, "a.example")
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTING), tab("b", ConnectionStatus.CONNECTING, second)))
        assertEquals(MainViewModel.TabPrompt("b", second), vm.activePrompt.value)

        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.CONNECTING, first), tab("b", ConnectionStatus.CONNECTING, second)),
        )
        assertEquals(MainViewModel.TabPrompt("a", first), vm.activePrompt.value)
    }

    @Test
    fun `connect errors are listed per tab`() {
        val vm = vm()
        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.DISCONNECTED, error = SshError.AuthFailed), tab("b", ConnectionStatus.CONNECTED)),
        )
        assertEquals(listOf(MainViewModel.ConnectError("a", SshError.AuthFailed)), vm.connectErrors.value)
    }

    @Test
    fun `the service is needed while a session connects or runs`() {
        val vm = vm()
        assertFalse(vm.serviceNeeded.value)
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTING)))
        assertTrue(vm.serviceNeeded.value)
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.DISCONNECTED)))
        assertFalse(vm.serviceNeeded.value)
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    @Test
    fun `an upload reports progress, then offers an undo that deletes the file`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED)))
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<MainViewModel.Upload?>()
        coEvery { sessions.upload("a", any(), "f.txt", 10, any()) } coAnswers {
            arg<(Long, Long) -> Unit>(4)(4, 10)
            gate.await()
        }
        val vm = vm()
        vm.startUpload("a", "f.txt", 10) { ByteArrayInputStream(ByteArray(10)) }
        seen += vm.upload.value
        gate.complete(Unit)

        assertEquals(listOf(MainViewModel.Upload.Running("f.txt", 4, 10)), seen)
        val done = vm.upload.value as MainViewModel.Upload.Done
        assertEquals(MainViewModel.Upload.Done("a", "f.txt"), done)

        vm.onUploadShown()
        assertNull(vm.upload.value)
        vm.undoUpload(done)
        coVerify { sessions.deleteRemote("a", "f.txt") }
        assertEquals(Message.UploadUndone("f.txt"), vm.message.value)
    }

    @Test
    fun `uploading to a tab that is not connected is refused`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.DISCONNECTED)))
        val vm = vm()
        vm.startUpload("a", "f", 1) { error("must not be opened") }
        assertEquals(MainViewModel.Upload.NotConnected, vm.upload.value)
        vm.startUpload(null, "f", 1) { error("must not be opened") }
        assertEquals(MainViewModel.Upload.NotConnected, vm.upload.value)
    }

    @Test
    fun `a failed upload reports why, and a failed undo too`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED)))
        coEvery { sessions.upload(any(), any(), any(), any(), any()) } throws java.io.IOException("disk full")
        coEvery { sessions.deleteRemote(any(), any()) } throws java.io.IOException("gone")
        val vm = vm()
        vm.startUpload("a", "f", 1) { ByteArrayInputStream(ByteArray(1)) }
        assertEquals(MainViewModel.Upload.Failed("disk full"), vm.upload.value)

        vm.undoUpload(MainViewModel.Upload.Done("a", "f"))
        assertEquals(Message.UndoFailed("gone"), vm.message.value)
    }

    @Test
    fun `showing an outcome does not clear a running upload`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED)))
        val gate = CompletableDeferred<Unit>()
        coEvery { sessions.upload(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = vm()
        vm.startUpload("a", "f", 1) { ByteArrayInputStream(ByteArray(1)) }
        vm.onUploadShown()
        assertTrue(vm.upload.value is MainViewModel.Upload.Running)
        gate.complete(Unit)
    }

    // ── Problem reports and messages ──────────────────────────────────────────

    @Test
    fun `sharing the problem report offers the latest one, or says there is none`() {
        val vm = vm()
        vm.shareLatestProblemReport()
        assertEquals(Message.NoProblemReport, vm.message.value)

        latestReport = File("report.txt")
        vm.shareLatestProblemReport()
        assertEquals(latestReport, vm.reportToShare.value)
        vm.onReportShared()
        assertNull(vm.reportToShare.value)
    }

    @Test
    fun `opening a connection that is gone is reported`() {
        val vm = vm()
        vm.openTab("missing")
        assertEquals(Message.Error(SshError.ConnectionMissing), vm.message.value)
        vm.onMessageShown()
        assertNull(vm.message.value)
    }
}
