package hu.billman.konsolessh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ui.ConnectionListScreen
import hu.billman.konsolessh.ui.ConnectionsViewModel
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val gateway = ConnectionConfig(id = "gw", name = "Gateway", host = "gw.example", username = "admin")
    private val pi = ConnectionConfig(id = "pi", host = "10.0.0.5", port = 2222, username = "pi")

    private fun show(state: ConnectionsViewModel.UiState, events: MutableList<String> = mutableListOf()) {
        compose.setContent {
            KonsoleTheme {
                ConnectionListScreen(
                    state = state,
                    onBack = { events += "back" },
                    onAdd = { events += "add" },
                    onEdit = { events += "edit:${it.id}" },
                    onDelete = { events += "delete:${it.id}" },
                )
            }
        }
    }

    @Test
    fun listsEveryConnectionWithItsAddress() {
        show(ConnectionsViewModel.UiState(listOf(gateway, pi)))
        compose.onNodeWithText("Gateway").assertIsDisplayed()
        compose.onNodeWithText("admin@gw.example:22").assertIsDisplayed()
        compose.onNodeWithText("pi@10.0.0.5").assertIsDisplayed()
        compose.onNodeWithText("pi@10.0.0.5:2222").assertIsDisplayed()
    }

    @Test
    fun anEmptyListSaysSo() {
        show(ConnectionsViewModel.UiState())
        compose.onNodeWithText(context.getString(R.string.no_saved_connections)).assertIsDisplayed()
    }

    @Test
    fun deletingAsksFirstAndOnlyConfirmingDeletes() {
        val events = mutableListOf<String>()
        show(ConnectionsViewModel.UiState(listOf(gateway)), events)
        val delete = context.getString(R.string.content_desc_delete)
        val question = context.getString(R.string.delete_connection_confirm, "Gateway")

        compose.onAllNodesWithContentDescription(delete)[0].performClick()
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(emptyList<String>(), events)

        compose.onAllNodesWithContentDescription(delete)[0].performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        assertEquals(listOf("delete:gw"), events)
    }

    @Test
    fun editAddAndBackAreReported() {
        val events = mutableListOf<String>()
        show(ConnectionsViewModel.UiState(listOf(gateway)), events)
        listOf(R.string.content_desc_edit, R.string.content_desc_add_connection, R.string.content_desc_back)
            .forEach { compose.onAllNodesWithContentDescription(context.getString(it))[0].performClick() }
        assertEquals(listOf("edit:gw", "add", "back"), events)
    }
}
