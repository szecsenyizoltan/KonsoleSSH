package hu.billman.konsolessh

import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A főképernyő kritikus folyamatai valódi felülettel, hamis munkamenettel
 * ([FakeTerminalSession]): kérdés és kimenet Activity-újraépülés után, és a
 * Vissza gomb, amely háttérbe küld, de a munkamenetet életben hagyja.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFlowTest {

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
    }

    private fun openFakeTab() {
        onView(withId(R.id.btnNewTab)).perform(click())
        onView(allOf(withId(R.id.itemTitle), withText(connection.name))).perform(click())
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
            onView(withText(R.string.hostkey_unknown_title)).inRoot(isDialog()).check(matches(isDisplayed()))

            scenario.recreate()

            onView(withText(R.string.hostkey_unknown_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.hostkey_accept)).inRoot(isDialog()).perform(click())
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
    }
}
