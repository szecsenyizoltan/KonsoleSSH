package hu.billman.konsolessh.ui.form

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.Auth
import hu.billman.konsolessh.model.ConnectionConfig.Jump
import hu.billman.konsolessh.ssh.ConnectionSetup
import hu.billman.konsolessh.ssh.GeneratedKey
import hu.billman.konsolessh.ssh.KeyGenerationException
import hu.billman.konsolessh.ssh.SshKeyFactory
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import hu.billman.konsolessh.ssh.SshKeyProvisioner.ServerType
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.InvalidReason
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.KeyDialog
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.KeyFileProblem
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.Message
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.Status
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

@OptIn(ExperimentalCoroutinesApi::class)
class NewConnectionViewModelTest {

    private val provisioner = mockk<SshKeyProvisioner>()

    /** The server banners keys were generated for. */
    private val generatedFor = mutableListOf<String>()
    private var keyFactory = SshKeyFactory { serverVersion, comment ->
        generatedFor += serverVersion
        GeneratedKey(privatePem = FAKE_PRIVATE_KEY, publicLine = "ssh-ed25519 AAAAfake $comment")
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        vararg stored: ConnectionConfig,
        repo: FakeConnectionRepository = FakeConnectionRepository(stored.toList()),
    ) = NewConnectionViewModel(repo, ConnectionSetup(repo, provisioner, keyFactory), UnconfinedTestDispatcher())

    private fun NewConnectionViewModel.fill(host: String = "203.0.113.5", password: String = "pw") {
        setHost(host)
        setUsername("root")
        setPassword(password)
    }

    private fun testSucceeds() {
        coEvery { provisioner.testConnection(any(), any(), any()) } returns
            SshKeyProvisioner.TestOutcome("SSH-2.0-OpenSSH_9.6", ServerType.OPENSSH)
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    @Test
    fun `editing fills the form and offers the other connections as jump hosts`() {
        val gw = ConnectionConfig(id = "gw", name = "Gateway", host = "gw.example", username = "u")
        val db = ConnectionConfig(
            id = "db",
            name = "DB",
            host = "10.1.2.3",
            port = 2222,
            username = "admin",
            auth = Auth.PrivateKey("KEY"),
            jump = Jump.Saved("gw"),
        )
        val vm = vm(gw, db).apply { start("db") }

        val form = vm.form.value
        assertTrue(form.loaded && form.editing)
        assertEquals(listOf("DB", "10.1.2.3", "2222", "admin"), listOf(form.name, form.host, form.port, form.username))
        assertTrue(form.usesKey && form.hasKey)
        assertEquals("gw", form.jumpId)
        assertEquals(listOf(NewConnectionViewModel.JumpOption("gw", "Gateway")), form.jumpOptions)
        assertTrue(form.isInternalHost && form.showJump)
    }

    @Test
    fun `a jump host that no longer exists is dropped`() {
        val stored = ConnectionConfig(id = "a", host = "h", username = "u", jump = Jump.Saved("gone"))
        val vm = vm(stored).apply { start("a") }
        assertNull(vm.form.value.jumpId)
    }

    @Test
    fun `a new connection starts with the most used user name`() {
        val vm = vm(
            ConnectionConfig(id = "1", host = "a", username = "pi"),
            ConnectionConfig(id = "2", host = "b", username = "pi"),
            ConnectionConfig(id = "3", host = "c", username = "root"),
        ).apply { start(null) }
        assertEquals("pi", vm.form.value.username)
        assertFalse(vm.form.value.editing)
    }

    @Test
    fun `an unreadable store is shown in the status line`() {
        val repo = FakeConnectionRepository().apply { unreadable = true }
        val vm = vm(repo = repo).apply { start(null) }
        assertEquals(Status.StoreUnreadable, vm.form.value.status)
        assertTrue(vm.form.value.loaded)
    }

    @Test
    fun `the jump section opens for private hosts or on request`() {
        val vm = vm().apply { start(null) }
        vm.setHost("example.org")
        assertFalse(vm.form.value.showJump)
        vm.setHost("192.168.1.20")
        assertTrue(vm.form.value.showJump)
        vm.setHost("example.org")
        vm.expandJump()
        assertTrue(vm.form.value.showJump)
    }

    // ── Testing ───────────────────────────────────────────────────────────────

    @Test
    fun `incomplete forms are not tested`() {
        val vm = vm().apply { start(null) }
        vm.test()
        assertEquals(Status.Invalid(InvalidReason.NEEDS_HOST_AND_USER), vm.form.value.status)
        vm.fill(password = "")
        vm.test()
        assertEquals(Status.Invalid(InvalidReason.NEEDS_PASSWORD), vm.form.value.status)
        vm.setAuthType(AuthMethod.PRIVATE_KEY)
        vm.test()
        assertEquals(Status.Invalid(InvalidReason.NEEDS_PRIVATE_KEY), vm.form.value.status)
    }

    @Test
    fun `a passed password test allows key generation until the credentials change`() {
        testSucceeds()
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        assertEquals(Status.TestSucceeded("SSH-2.0-OpenSSH_9.6"), vm.form.value.status)
        assertTrue(vm.form.value.canGenerateKey)

        vm.setName("only the name")
        assertTrue(vm.form.value.canGenerateKey)

        vm.setPassword("changed")
        assertFalse(vm.form.value.canGenerateKey)
        assertEquals(Status.None, vm.form.value.status)
    }

    @Test
    fun `a passed key test does not allow key generation`() {
        testSucceeds()
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.setAuthType(AuthMethod.PRIVATE_KEY)
        vm.setPrivateKey("KEY")
        vm.test()
        assertTrue(vm.form.value.status is Status.TestSucceeded)
        assertFalse(vm.form.value.canGenerateKey)
    }

    @Test
    fun `credentials changed while the test runs are not counted as tested`() {
        val outcome = CompletableDeferred<SshKeyProvisioner.TestOutcome>()
        coEvery { provisioner.testConnection(any(), any(), any()) } coAnswers { outcome.await() }
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        assertEquals(Status.Testing, vm.form.value.status)
        assertTrue(vm.form.value.busy)

        vm.setPassword("typed during the test")
        outcome.complete(SshKeyProvisioner.TestOutcome("v", ServerType.OPENSSH))

        assertFalse(vm.form.value.busy)
        assertFalse(vm.form.value.canGenerateKey)
    }

    @Test
    fun `a failed test is shown and allows nothing`() {
        coEvery { provisioner.testConnection(any(), any(), any()) } throws java.net.UnknownHostException("nope")
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        assertTrue(vm.form.value.status is Status.TestFailed)
        assertFalse(vm.form.value.canGenerateKey)
    }

    // ── Key upload ────────────────────────────────────────────────────────────

    @Test
    fun `key generation without a fresh test only asks for one`() {
        val vm = vm().apply { start(null) }
        vm.generateKey()
        assertEquals(Message.NeedsTest, vm.message.value)
    }

    @Test
    fun `a successful upload switches the form to the new key`() {
        testSucceeds()
        coEvery { provisioner.uploadPublicKey(any(), any(), any(), any()) } returns ServerType.OPENSSH
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        vm.generateKey()

        val form = vm.form.value
        assertEquals(Status.UploadSucceeded(ServerType.OPENSSH), form.status)
        assertTrue(form.usesKey)
        assertEquals(FAKE_PRIVATE_KEY, form.privateKey)
        assertEquals("", form.password)
        assertFalse(form.canGenerateKey)
        assertTrue(vm.keyDialog.value is KeyDialog.Uploaded)
    }

    @Test
    fun `a failed upload keeps the test and offers the key for a manual install`() {
        testSucceeds()
        coEvery { provisioner.uploadPublicKey(any(), any(), any(), any()) } throws java.io.IOException("cut")
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        vm.generateKey()

        assertTrue(vm.form.value.status is Status.UploadFailed)
        assertTrue(vm.form.value.canGenerateKey)
        assertEquals("ssh-ed25519 AAAAfake konsolessh:root@203.0.113.5", vm.keyDialog.value!!.publicLine)
    }

    @Test
    fun `a failed key generation uploads nothing and restores the status`() {
        testSucceeds()
        keyFactory = SshKeyFactory { _, _ -> throw KeyGenerationException(IllegalStateException("no entropy")) }
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        val tested = vm.form.value.status
        vm.generateKey()

        assertTrue(vm.message.value is Message.KeyGenerationFailed)
        assertEquals(tested, vm.form.value.status)
        assertNull(vm.keyDialog.value)
        coVerify(exactly = 0) { provisioner.uploadPublicKey(any(), any(), any(), any()) }
    }

    @Test
    fun `the key is generated for the tested server's banner`() {
        coEvery { provisioner.testConnection(any(), any(), any()) } returns
            SshKeyProvisioner.TestOutcome("SSH-2.0-ROSSSH", ServerType.MIKROTIK)
        coEvery { provisioner.uploadPublicKey(any(), any(), any(), any()) } returns ServerType.MIKROTIK
        val vm = vm().apply { start(null) }
        vm.fill()
        vm.test()
        vm.generateKey()

        assertEquals(listOf("SSH-2.0-ROSSSH"), generatedFor)
    }

    // ── Key files ─────────────────────────────────────────────────────────────

    @Test
    fun `a picked key file fills the private key`() {
        val vm = vm().apply { start(null) }
        vm.loadKeyFile("/keys/id_ed25519") { FAKE_PRIVATE_KEY.byteInputStream() }
        assertEquals(FAKE_PRIVATE_KEY, vm.form.value.privateKey)
        assertNull(vm.message.value)
    }

    @Test
    fun `an oversized or unreadable key file is reported, not loaded`() {
        val vm = vm().apply { start(null) }
        vm.loadKeyFile("/big") { ByteArray(64 * 1024 + 1).inputStream() }
        assertEquals(Message.KeyFileUnreadable(KeyFileProblem.TOO_LARGE), vm.message.value)

        vm.loadKeyFile("/denied") { throw SecurityException("no permission") }
        assertEquals(Message.KeyFileUnreadable(KeyFileProblem.UNREADABLE), vm.message.value)
        assertEquals("", vm.form.value.privateKey)
    }

    @Test
    fun `a public key file is loaded with a warning`() {
        val vm = vm().apply { start(null) }
        vm.loadKeyFile("/keys/id_ed25519.PUB") { "ssh-ed25519 AAAA".byteInputStream() }
        assertEquals(Message.PublicKeyPicked, vm.message.value)
        assertEquals("ssh-ed25519 AAAA", vm.form.value.privateKey)
    }

    // ── Saving ────────────────────────────────────────────────────────────────

    @Test
    fun `saving needs a host and a user name`() {
        val vm = vm().apply { start(null) }
        vm.save()
        assertEquals(Message.HostRequired, vm.message.value)
        vm.setHost("h")
        vm.save()
        assertEquals(Message.UsernameRequired, vm.message.value)
        assertNull(vm.savedId.value)
    }

    @Test
    fun `saving an edit keeps the fields the form does not show`() = runBlocking {
        val stored = ConnectionConfig(
            id = "a",
            host = "h",
            username = "u",
            auth = Auth.PrivateKey("KEY", passphrase = "pass"),
            jump = Jump.Manual("manual.example", port = 2200, username = "j", password = "jp"),
        )
        val repo = FakeConnectionRepository(listOf(stored))
        val vm = vm(repo = repo).apply { start("a") }
        vm.setName("Renamed")
        vm.setPort("99999")
        vm.save()

        assertEquals("a", vm.savedId.value)
        val saved = repo.get("a")!!
        assertEquals("Renamed", saved.name)
        assertEquals(65535, saved.port)
        assertEquals("pass", (saved.auth as Auth.PrivateKey).passphrase)
        assertEquals(stored.jump, saved.jump)
    }

    @Test
    fun `picking a saved jump host replaces an old manual one`() = runBlocking {
        val gw = ConnectionConfig(id = "gw", host = "gw.example", username = "u")
        val manual = Jump.Manual("old.example", username = "j", password = "jp")
        val stored = ConnectionConfig(id = "a", host = "h", username = "u", jump = manual)
        val repo = FakeConnectionRepository(listOf(gw, stored))
        val vm = vm(repo = repo).apply { start("a") }
        vm.selectJump("gw")
        vm.save()
        assertEquals(Jump.Saved("gw"), repo.get("a")!!.jump)
    }

    @Test
    fun `switching to a password drops the key and its passphrase`() = runBlocking {
        val stored = ConnectionConfig(id = "a", host = "h", username = "u", auth = Auth.PrivateKey("KEY", "pass"))
        val repo = FakeConnectionRepository(listOf(stored))
        val vm = vm(repo = repo).apply { start("a") }
        assertTrue(vm.form.value.usesKey)

        vm.setAuthType(AuthMethod.PASSWORD)
        vm.setPassword("new password")
        vm.save()
        assertEquals(Auth.Password("new password"), repo.get("a")!!.auth)
    }

    @Test
    fun `a key upload uses the tested password even after switching to key mode`() {
        testSucceeds()
        coEvery { provisioner.uploadPublicKey(any(), any(), any(), any()) } returns ServerType.OPENSSH
        val vm = vm().apply { start(null) }
        vm.fill(password = "pw")
        vm.test()
        vm.setAuthType(AuthMethod.PRIVATE_KEY)
        vm.generateKey()

        coVerify { provisioner.uploadPublicKey(match { it.auth == Auth.Password("pw") }, any(), any(), any()) }
    }

    @Test
    fun `a new connection keeps its id across saves`() = runBlocking {
        val repo = FakeConnectionRepository()
        val vm = vm(repo = repo).apply { start(null) }
        vm.fill()
        vm.save()
        val first = vm.savedId.value
        vm.save()
        assertEquals(first, vm.savedId.value)
        assertEquals(1, repo.load().size)
    }

    @Test
    fun `a failed save reports it and saves nothing`() {
        val repo = FakeConnectionRepository()
        val vm = vm(repo = repo).apply { start(null) }
        vm.fill()
        repo.unreadable = true
        vm.save()
        assertEquals(Message.StoreWriteFailed, vm.message.value)
        assertNull(vm.savedId.value)
    }

    private companion object {
        const val FAKE_PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
