package hu.billman.konsolessh

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.billman.konsolessh.ui.form.AuthMethod
import hu.billman.konsolessh.ui.form.NewConnectionActions
import hu.billman.konsolessh.ui.form.NewConnectionForm
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.FormState
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.JumpOption
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.Message
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.PasswordTest
import hu.billman.konsolessh.ui.form.NewConnectionViewModel.Status
import hu.billman.konsolessh.ui.form.STATUS_TAG
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewConnectionFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val events = mutableListOf<String>()

    private fun actions() = NewConnectionActions(
        onName = { events += "name:$it" },
        onHost = { events += "host:$it" },
        onPort = { events += "port:$it" },
        onUsername = { events += "user:$it" },
        onPassword = { events += "password:$it" },
        onAuthType = { events += "auth:$it" },
        onJump = { events += "jump:$it" },
        onExpandJump = { events += "expandJump" },
        onPickKeyFile = { events += "pickKey" },
        onTest = { events += "test" },
        onGenerateKey = { events += "generate" },
        onSave = { events += "save" },
        onCancel = { events += "cancel" },
        onMessageShown = { events += "messageShown" },
        onKeyDialogShown = { events += "keyDialogShown" },
    )

    private fun show(form: FormState, message: Message? = null) = compose.setContent {
        KonsoleTheme {
            NewConnectionForm(form, message, keyDialog = null, errorText = { it.toString() }, actions = actions())
        }
    }

    @Test
    fun typingIntoAPrefilledFieldAppendsAtTheEnd() {
        var form by mutableStateOf(FormState())
        compose.setContent {
            KonsoleTheme {
                NewConnectionForm(form, null, keyDialog = null, errorText = { it.toString() }, actions = actions())
            }
        }
        // The ViewModel fills the usual user name in after the form is shown.
        form = form.copy(username = "root")
        compose.waitForIdle()

        compose.onNodeWithText("root").performTextInput("x")

        assertEquals("user:rootx", events.last())
    }

    @Test
    fun aPrivateHostGetsInternalLabelsAndTheJumpSection() {
        show(FormState(host = "192.168.1.5", jumpOptions = listOf(JumpOption("gw", "Gateway"))))
        compose.onNodeWithText(context.getString(R.string.host_internal_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.jump_host_section_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.jump_host_none)).assertIsDisplayed()
    }

    @Test
    fun aPublicHostOffersTheJumpSectionOnRequest() {
        show(FormState(host = "example.org"))
        compose.onNodeWithText(context.getString(R.string.host_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.configure_jump_host)).performClick()
        assertEquals(listOf("expandJump"), events)
    }

    @Test
    fun typingIsReportedFieldByField() {
        show(FormState())
        compose.onNodeWithText(context.getString(R.string.username_hint)).performTextInput("root")
        compose.onNodeWithText(context.getString(R.string.password_hint_optional)).performTextInput("pw")
        assertTrue(events.toString(), "user:root" in events && "password:pw" in events)
    }

    @Test
    fun thePasswordIsHiddenUntilShown() {
        show(FormState(password = "secret"))
        val field = compose.onNodeWithText(context.getString(R.string.password_hint_optional))
        fun shows(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))
        field.assert(shows("••••••"))
        compose.onNodeWithContentDescription(context.getString(R.string.content_desc_show_password)).performClick()
        field.assert(shows("secret"))
    }

    @Test
    fun keyModeShowsKeyActionsAndGenerationFollowsTheState() {
        show(FormState(authType = AuthMethod.PRIVATE_KEY, privateKey = "KEY"))
        compose.onNodeWithText(context.getString(R.string.sym_check)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.generate_and_upload_key)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.pick_key_file)).performClick()
        assertEquals(listOf("pickKey"), events)
    }

    @Test
    fun aFreshPasswordTestEnablesKeyGeneration() {
        val tested = FormState(host = "h", username = "u", password = "p")
        show(
            tested.copy(
                authType = AuthMethod.PRIVATE_KEY,
                passwordTest = PasswordTest(tested.credentials(), "SSH-2.0-OpenSSH_9.6"),
            ),
        )
        compose.onNodeWithText(context.getString(R.string.generate_and_upload_key)).assertIsEnabled()
    }

    @Test
    fun theStatusLineAndTheButtonsFollowTheState() {
        show(FormState(status = Status.Testing, busy = true))
        compose.onNodeWithTag(STATUS_TAG).assertTextEquals(context.getString(R.string.test_running))
        compose.onNodeWithText(context.getString(R.string.action_test_connection)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(listOf("save", "cancel"), events)
    }

    @Test
    fun aMessageIsShownAndReported() {
        show(FormState(), message = Message.HostRequired)
        compose.onNodeWithText(context.getString(R.string.error_host_required)).assertIsDisplayed()
        assertTrue("messageShown" in events)
    }
}
