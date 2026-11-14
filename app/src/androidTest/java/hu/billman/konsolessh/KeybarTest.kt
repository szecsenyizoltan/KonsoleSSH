package hu.billman.konsolessh

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.terminal.KeySequences
import hu.billman.konsolessh.terminal.Modifier
import hu.billman.konsolessh.terminal.ModifierState
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.keybar.Keybar
import hu.billman.konsolessh.ui.keybar.KeybarHost
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeybarTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class RecordingHost : KeybarHost {
        val keys = mutableListOf<ByteArray>()
        val raw = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        override fun currentTerminal(): TerminalView? = null
        override fun sendKey(bytes: ByteArray) {
            keys += bytes
        }
        override fun sendRaw(bytes: ByteArray) {
            raw += bytes
        }
        override fun toggleModifier(modifier: Modifier) {
            events += "toggle:$modifier"
        }
        override fun toggleKeyboard() {
            events += "keyboard"
        }
        override fun pickUploadFile() {
            events += "upload"
        }
        override fun showMessage(message: String) {
            events += "message:$message"
        }
    }

    private fun show(modifiers: ModifierState = ModifierState()): RecordingHost {
        val host = RecordingHost()
        compose.setContent { KonsoleTheme { Keybar(modifiers, host) } }
        return host
    }

    @Test
    fun modifiersEscTabKeyboardAndUploadAreReported() {
        val host = show()
        compose.onNodeWithText("CTRL").performClick()
        compose.onNodeWithText("ALTGR").performClick()
        compose.onNodeWithText(context.getString(R.string.sym_keyboard)).performClick()
        // The row scrolls; at its end ESC is clear of the edge arrow that covers a key at the edge.
        compose.onNodeWithContentDescription(context.getString(R.string.content_desc_upload))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("ESC").performClick()
        assertEquals(listOf("toggle:CTRL", "toggle:ALTGR", "keyboard", "upload"), host.events)
        assertArrayEquals(byteArrayOf(KeySequences.ESC), host.keys.single())
    }

    @Test
    fun theFunctionRowOpensAndSendsFunctionKeys() {
        val host = show()
        compose.onNodeWithText("F1").assertDoesNotExist()
        compose.onNodeWithText("FN").performClick()
        compose.onNodeWithText("F1").performClick()
        assertArrayEquals(KeySequences.escape(KeySequences.FUNCTION_KEYS[0]), host.keys.single())
    }

    @Test
    fun theCtrlRowIsHiddenWhileCtrlIsOff() {
        show()
        compose.onNodeWithText("Ctrl+D").assertDoesNotExist()
    }

    @Test
    fun ctrlComboSendsTheControlCodeWithoutModifiers() {
        val host = show(ModifierState(setOf(Modifier.CTRL)))
        compose.onNodeWithText("Ctrl+D").performClick()
        assertArrayEquals(byteArrayOf(4), host.raw.single())
        assertEquals(emptyList<ByteArray>(), host.keys)
    }
}
