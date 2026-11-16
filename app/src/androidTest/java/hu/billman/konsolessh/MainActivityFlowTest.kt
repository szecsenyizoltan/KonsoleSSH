package hu.billman.konsolessh

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.ui.KonsoleViewModelFactory
import hu.billman.konsolessh.ui.MainActivity
import hu.billman.konsolessh.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

/**
 * The main screen's critical flows with the real UI and a fake session
 * ([FakeTerminalSession]): a question and the output after the activity is
 * recreated, moving between the screens, and the Back button, which moves
 * the app to the background but keeps the session alive.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFlowTest {

    /** Finds Compose nodes in any window of the app, the tab picker's sheet included. */
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.toTypedArray(),
    )

    private val app = ApplicationProvider.getApplicationContext<KonsoleApplication>()
    private val container get() = app.container
    private val connection = ConnectionConfig(id = "fake", name = "fake-server", host = "fake.example", username = "u")

    @Before
    fun setUp() = runBlocking {
        container.sessionManager.closeAll()
        container.connectionRepository.saveOne(connection)
    }

    @After
    fun tearDown() = runBlocking {
        container.sessionManager.closeAll()
        container.connectionRepository.delete(connection.id)
        FakeTerminalSession.askHostKey = true
        FakeTerminalSession.uploadGate?.complete(Unit)
        FakeTerminalSession.uploadGate = null
        FakeTerminalSession.uploaded.clear()
    }

    private fun openFakeTab() {
        compose.onNodeWithContentDescription(app.getString(R.string.content_desc_new_tab)).performClick()
        // The saved connection's row; with a tab open, the open-tabs list shows the name too.
        compose.onNodeWithText("${connection.username}@${connection.host}:${connection.port}").performClick()
    }

    /**
     * Waits for [condition]. Compose only draws frames when the test syncs with
     * it, so the wait goes through the Compose rule: a page that has to be
     * composed (and lay out its terminal to connect) gets its frames.
     */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(TIMEOUT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            fail("timed out: $what (${container.sessionManager.state.value.tabs})")
        }
    }

    private fun tabStatus() = container.sessionManager.state.value.tabs.singleOrNull()?.status

    @Test
    fun hostKeyQuestionSurvivesRecreationAndTheAnswerConnects() {
        FakeTerminalSession.askHostKey = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            compose.onNodeWithText(app.getString(R.string.hostkey_unknown_title)).assertIsDisplayed()

            scenario.recreate()

            compose.onNodeWithText(app.getString(R.string.hostkey_unknown_title)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.hostkey_accept)).performClick()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }
            compose.onNodeWithText(app.getString(R.string.status_connected_suffix, connection.name)).assertIsDisplayed()
        }
    }

    @Test
    fun outputIsShownExactlyOnceAfterRepeatedRecreation() {
        FakeTerminalSession.askHostKey = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }
            repeat(RECREATIONS) { scenario.recreate() }

            fun screenText(): String {
                var text = ""
                scenario.onActivity { activity ->
                    // Right after recreation the page may not be composed yet.
                    val buffer = activity.currentTerminal()?.buffer ?: return@onActivity
                    text = (0 until buffer.totalLines).joinToString("\n") { line ->
                        buffer.lineAt(line).orEmpty().joinToString("") { it.ch }
                    }
                }
                return text
            }
            await("greeting rendered") { "FAKE-GREETING" in screenText() }
            // Rendering runs off the main thread; give a late duplicate the chance to appear.
            Thread.sleep(SETTLE_MS)
            assertEquals(1, Regex("FAKE-GREETING").findAll(screenText()).count())
        }
    }

    @Test
    fun anUploadKeepsRunningThroughRecreation() {
        FakeTerminalSession.askHostKey = false
        val gate = CompletableDeferred<Unit>().also { FakeTerminalSession.uploadGate = it }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }
            val tabId = container.sessionManager.state.value.tabs.single().id
            scenario.onActivity { activity ->
                val factory = KonsoleViewModelFactory.from(activity)
                val viewModel = ViewModelProvider(activity, factory)[MainViewModel::class.java]
                viewModel.startUpload(tabId, UPLOAD_NAME, 2) { ByteArrayInputStream(ByteArray(2)) }
            }
            compose.onNodeWithText(app.getString(R.string.upload_title, UPLOAD_NAME)).assertIsDisplayed()

            scenario.recreate()

            compose.onNodeWithText(app.getString(R.string.upload_title, UPLOAD_NAME)).assertIsDisplayed()
            gate.complete(Unit)
            await("upload finished") { UPLOAD_NAME in FakeTerminalSession.uploaded }
        }
    }

    @Test
    fun tappingATabInTheStripSwitchesToIt() {
        FakeTerminalSession.askHostKey = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            openFakeTab()
            await("two tabs") { container.sessionManager.state.value.tabs.size == 2 }
            var before: Any? = null
            scenario.onActivity { before = it.currentTerminal() }

            // Both tabs carry the connection's name; the first one in the strip is the first tab.
            compose.onAllNodesWithText(connection.name)[0].performClick()

            await("first tab shown") {
                var current: Any? = null
                scenario.onActivity { current = it.currentTerminal() }
                current != null && current !== before
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun closingTheLastTabShowsTheWelcomePage() {
        FakeTerminalSession.askHostKey = false
        val welcome = app.getString(R.string.welcome_line_1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }

            container.sessionManager.closeTab(container.sessionManager.state.value.tabs.single().id)

            await("welcome page shown") {
                var text = ""
                scenario.onActivity { activity ->
                    val buffer = activity.currentTerminal()?.buffer ?: return@onActivity
                    text = (0 until buffer.totalLines).joinToString("\n") { line ->
                        buffer.lineAt(line).orEmpty().joinToString("") { it.ch }
                    }
                }
                welcome in text
            }
        }
    }

    private fun openMenuItem(label: Int) {
        compose.onNodeWithContentDescription(app.getString(R.string.content_desc_more)).performClick()
        compose.onNodeWithText(app.getString(label)).performClick()
    }

    @Test
    fun backFromTheConnectionsScreenReturnsToTheTerminal() {
        FakeTerminalSession.askHostKey = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }

            openMenuItem(R.string.manage_connections)
            val add = app.getString(R.string.content_desc_add_connection)
            compose.onNodeWithContentDescription(add).assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithContentDescription(add).assertIsDisplayed()

            pressBack()

            await("terminal shown again") {
                var shown = false
                scenario.onActivity { shown = it.currentTerminal() != null }
                shown
            }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals(ConnectionStatus.CONNECTED, tabStatus())
        }
    }

    @Test
    fun theSyncScreenClosesWithItsBackButton() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openMenuItem(R.string.menu_sync)
            compose.onNodeWithContentDescription(app.getString(R.string.content_desc_back)).performClick()

            compose.onNodeWithContentDescription(app.getString(R.string.content_desc_new_tab)).assertIsDisplayed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backSendsTheAppToTheBackgroundAndKeepsTheSession() {
        FakeTerminalSession.askHostKey = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openFakeTab()
            await("connected") { tabStatus() == ConnectionStatus.CONNECTED }

            pressBackUnconditionally()

            await("activity stopped") { scenario.state == Lifecycle.State.CREATED }
            assertEquals(ConnectionStatus.CONNECTED, tabStatus())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 500L
        const val RECREATIONS = 2
        const val UPLOAD_NAME = "upload-test.txt"
    }
}
