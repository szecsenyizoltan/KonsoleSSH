package hu.billman.konsolessh

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
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
 * A főképernyő kritikus folyamatai valódi felülettel, hamis munkamenettel
 * ([FakeTerminalSession]): kérdés és kimenet Activity-újraépülés után, és a
 * Vissza gomb, amely háttérbe küld, de a munkamenetet életben hagyja.
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
        onView(withId(R.id.btnNewTab)).perform(click())
        compose.onNodeWithText(connection.name).performClick()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $what")
            Thread.sleep(POLL_MS)
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
            onView(withText(app.getString(R.string.status_connected_suffix, connection.name)))
                .check(matches(isDisplayed()))
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
                    val buffer = checkNotNull(activity.currentTerminal()).buffer
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
        const val POLL_MS = 50L
        const val SETTLE_MS = 500L
        const val RECREATIONS = 2
        const val UPLOAD_NAME = "upload-test.txt"
    }
}
