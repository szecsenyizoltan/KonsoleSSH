package hu.billman.konsolessh.ui

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.data.SettingsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val state = MutableStateFlow(SessionsState())
    private val sessions = mockk<SessionManager>(relaxed = true).also { every { it.state } returns state }
    private val settings = object : SettingsRepository {
        override val fontSizeSp = flowOf(0f)
        override suspend fun setFontSizeSp(value: Float) = Unit
        override val syncOfferDismissed = flowOf(false)
        override suspend fun dismissSyncOffer() = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MainViewModel(
        sessions,
        FakeConnectionRepository(),
        settings,
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
        assertNull(vm.state.value.confirm)

        vm.requestCloseTab("a")
        assertEquals(Confirm.CloseTab("a", "Tab a"), vm.state.value.confirm)
        verify(exactly = 0) { sessions.closeTab("a") }

        vm.confirmed(vm.state.value.confirm!!)
        verify { sessions.closeTab("a") }
    }

    @Test
    fun `exiting with live connections asks first and names how many`() {
        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.CONNECTED), tab("b", ConnectionStatus.CONNECTED), tab("c", ConnectionStatus.CONNECTING)),
        )
        val vm = vm()

        vm.requestCloseApp()
        assertEquals(Confirm.CloseApp(2), vm.state.value.confirm)
        assertFalse(vm.state.value.exit)

        vm.confirmed(vm.state.value.confirm!!)
        verify { sessions.closeAll() }
        assertTrue(vm.state.value.exit)
    }

    @Test
    fun `exiting without connections needs no confirmation`() {
        val vm = vm()
        vm.requestCloseApp()
        assertNull(vm.state.value.confirm)
        assertTrue(vm.state.value.exit)
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
        assertEquals(MainViewModel.TabPrompt("b", second), vm.state.value.activePrompt)

        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.CONNECTING, first), tab("b", ConnectionStatus.CONNECTING, second)),
        )
        assertEquals(MainViewModel.TabPrompt("a", first), vm.state.value.activePrompt)
    }

    @Test
    fun `connect errors are listed per tab`() {
        val vm = vm()
        state.value = SessionsState(
            listOf(tab("a", ConnectionStatus.DISCONNECTED, error = SshError.AuthFailed), tab("b", ConnectionStatus.CONNECTED)),
        )
        assertEquals(listOf(MainViewModel.ConnectError("a", SshError.AuthFailed)), vm.state.value.connectErrors)
    }

    @Test
    fun `the service is needed while a session connects or runs`() {
        val vm = vm()
        assertFalse(vm.state.value.serviceNeeded)
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTING)))
        assertTrue(vm.state.value.serviceNeeded)
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.DISCONNECTED)))
        assertFalse(vm.state.value.serviceNeeded)
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
        seen += vm.state.value.upload
        gate.complete(Unit)

        assertEquals(listOf(MainViewModel.Upload.Running("f.txt", 4, 10)), seen)
        val done = vm.state.value.upload as MainViewModel.Upload.Done
        assertEquals(MainViewModel.Upload.Done("a", "f.txt"), done)

        vm.onUploadShown()
        assertNull(vm.state.value.upload)
        vm.undoUpload(done)
        coVerify { sessions.deleteRemote("a", "f.txt") }
        assertEquals(Message.UploadUndone("f.txt"), vm.state.value.message)
    }

    @Test
    fun `uploading to a tab that is not connected is refused`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.DISCONNECTED)))
        val vm = vm()
        vm.startUpload("a", "f", 1) { error("must not be opened") }
        assertEquals(MainViewModel.Upload.NotConnected, vm.state.value.upload)
        vm.startUpload(null, "f", 1) { error("must not be opened") }
        assertEquals(MainViewModel.Upload.NotConnected, vm.state.value.upload)
    }

    @Test
    fun `a failed upload reports why, and a failed undo too`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED)))
        coEvery { sessions.upload(any(), any(), any(), any(), any()) } throws java.io.IOException("disk full")
        coEvery { sessions.deleteRemote(any(), any()) } throws
            com.jcraft.jsch.SftpException(com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file")
        val vm = vm()
        vm.startUpload("a", "f", 1) { ByteArrayInputStream(ByteArray(1)) }
        assertEquals(MainViewModel.Upload.Failed(SshError.Other("disk full")), vm.state.value.upload)

        vm.undoUpload(MainViewModel.Upload.Done("a", "f"))
        assertEquals(Message.UndoFailed(SshError.RemoteFileMissing), vm.state.value.message)
    }

    @Test
    fun `showing an outcome does not clear a running upload`() {
        state.value = SessionsState(listOf(tab("a", ConnectionStatus.CONNECTED)))
        val gate = CompletableDeferred<Unit>()
        coEvery { sessions.upload(any(), any(), any(), any(), any()) } coAnswers { gate.await() }
        val vm = vm()
        vm.startUpload("a", "f", 1) { ByteArrayInputStream(ByteArray(1)) }
        vm.onUploadShown()
        assertTrue(vm.state.value.upload is MainViewModel.Upload.Running)
        gate.complete(Unit)
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    @Test
    fun `opening a connection that is gone is reported`() {
        val vm = vm()
        vm.openTab("missing")
        assertEquals(Message.Error(SshError.ConnectionMissing), vm.state.value.message)
        vm.onMessageShown()
        assertNull(vm.state.value.message)
    }
}
