package hu.billman.konsolessh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ui.common.PromptDialog
import hu.billman.konsolessh.ui.main.RenameTabDialog
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val answers = mutableListOf<PromptAnswer>()

    private fun showPrompt(prompt: PendingPrompt) = compose.setContent {
        KonsoleTheme { PromptDialog(prompt) { answers += it } }
    }

    @Test
    fun anUnknownHostKeyShowsItsFingerprintAndCanBeAccepted() {
        showPrompt(PendingPrompt.HostKey(1, HostKeyPrompt("srv.example", "ssh-ed25519", "SHA256:abc", changed = false)))
        compose.onNodeWithText(context.getString(R.string.hostkey_unknown_title)).assertIsDisplayed()
        compose.onNodeWithText("SHA256:abc", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hostkey_accept)).performClick()
        assertEquals(listOf(PromptAnswer.HostKey(true)), answers)
    }

    @Test
    fun aChangedHostKeyWarnsAndCanBeRejected() {
        showPrompt(PendingPrompt.HostKey(2, HostKeyPrompt("srv.example", "ssh-ed25519", "SHA256:new", changed = true)))
        compose.onNodeWithText(context.getString(R.string.hostkey_changed_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(listOf(PromptAnswer.HostKey(false)), answers)
    }

    @Test
    fun aPasswordIsSentOrCancelled() {
        showPrompt(PendingPrompt.Password(3, "pi@10.0.0.5"))
        compose.onNodeWithText(context.getString(R.string.password)).performTextInput("secret")
        compose.onNodeWithText(context.getString(R.string.action_ok)).performClick()
        assertEquals(listOf(PromptAnswer.Password("secret")), answers)
    }

    @Test
    fun renamingReplacesTheSelectedName() {
        var renamed: String? = null
        compose.setContent { KonsoleTheme { RenameTabDialog("old", onRename = { renamed = it }, onDismiss = {}) } }
        compose.onNodeWithText("old").performTextReplacement("prod")
        compose.onNodeWithText(context.getString(R.string.action_ok)).performClick()
        assertEquals("prod", renamed)
    }
}
