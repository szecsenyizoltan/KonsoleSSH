package hu.billman.konsolessh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.ui.PickerRows
import hu.billman.konsolessh.ui.TabPickerActions
import hu.billman.konsolessh.ui.TabPickerContent
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabPickerContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = mutableListOf<String>()
    private val web = ConnectionConfig(id = "web", name = "prod_web", host = "w.example", username = "u")
    private val db = ConnectionConfig(id = "db", name = "prod_db", host = "d.example", username = "u")
    private val pi = ConnectionConfig(id = "pi", name = "pi", host = "10.0.0.5", username = "pi")

    private fun show(expanded: Set<String> = emptySet()) = compose.setContent {
        KonsoleTheme {
            TabPickerContent(
                openTabs = listOf(
                    PickerRows.OpenTab(0, "pi", "10.0.0.5", ConnectionStatus.CONNECTED, active = true),
                    PickerRows.OpenTab(1, "shell-2", "w.example", ConnectionStatus.DISCONNECTED, active = false),
                ),
                saved = PickerRows.saved(listOf(web, db, pi), expanded),
                actions = TabPickerActions(
                    onNewConnection = { events += "new" },
                    onSelectTab = { events += "select:$it" },
                    onOpen = { events += "open:${it.id}" },
                    onEdit = { events += "edit:${it.id}" },
                    onDelete = { events += "delete:${it.id}" },
                    onToggleGroup = { events += "toggle:$it" },
                    onExit = { events += "exit" },
                ),
            )
        }
    }

    @Test
    fun openTabsAreListedWithTheActiveOneMarked() {
        show()
        compose.onNodeWithText(context.getString(R.string.tab_active)).assertIsDisplayed()
        compose.onNodeWithText("w.example").performClick()
        assertEquals(listOf("select:1"), events)
    }

    @Test
    fun aGroupOpensOnRequestAndListsItsConnections() {
        show()
        compose.onNodeWithText("prod").performClick()
        assertEquals(listOf("toggle:prod_"), events)
        compose.onNodeWithText("web").assertDoesNotExist()
    }

    @Test
    fun anExpandedGroupShowsItsConnectionsByTheirShortName() {
        show(expanded = setOf("prod_"))
        compose.onNodeWithText("web").performClick()
        assertEquals(listOf("open:web"), events)
    }

    @Test
    fun deletingAsksFirst() {
        show()
        compose.onAllNodesWithContentDescription(context.getString(R.string.content_desc_delete))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.delete_connection_confirm, "pi")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        assertEquals(listOf("delete:pi"), events)
    }

    @Test
    fun newConnectionAndExitAreReported() {
        show()
        compose.onNodeWithText(context.getString(R.string.new_connection_dots)).performClick()
        compose.onNodeWithText(context.getString(R.string.exit)).performClick()
        assertEquals(listOf("new", "exit"), events)
    }
}
