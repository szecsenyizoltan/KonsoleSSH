package hu.billman.konsolessh.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Hosts the Compose [NewConnectionForm] in a dialog window while the screens
 * that open it are still Fragment-based. The form's state and rules live in
 * [NewConnectionViewModel]; this class only reads key files, shows host key
 * questions and returns the saved id through the Fragment Result API
 * ([RESULT_KEY]), so the result arrives after a rotation too.
 */
class NewConnectionDialog : DialogFragment() {

    companion object {
        const val RESULT_KEY = "new_connection_result"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_OPEN_TAB = "open_tab"
        private const val ARG_EDIT_ID = "edit_id"
        private const val ARG_OPEN_TAB = "open_tab"

        /** A private key is a few KB; a larger file is certainly not one. */
        private const val MAX_KEY_FILE_BYTES = 64 * 1024
        private const val READ_CHUNK = 8192

        private fun InputStream.readAtMost(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(READ_CHUNK)
            while (out.size() < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** A new connection; with [openTab], a tab is opened for it after saving. */
        fun newForCreate(openTab: Boolean) = NewConnectionDialog().apply {
            arguments = bundleOf(ARG_OPEN_TAB to openTab)
        }

        fun newForEdit(connectionId: String) = NewConnectionDialog().apply {
            arguments = bundleOf(ARG_EDIT_ID to connectionId)
        }
    }

    private val viewModel: NewConnectionViewModel by viewModels { KonsoleViewModelFactory.from(requireContext()) }

    private var promptDialog: Dialog? = null
    private var shownPromptId: Long? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        if (uri.path.orEmpty().lowercase().endsWith(".pub")) viewModel.publicKeyPicked()
        // A document provider (e.g. a cloud drive) may block: read off the main thread.
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readAtMost(MAX_KEY_FILE_BYTES + 1)
                        if (bytes.size > MAX_KEY_FILE_BYTES) throw IOException("file is too large for a key")
                        String(bytes, Charsets.UTF_8)
                    }
                }
                if (content != null) viewModel.setPrivateKey(content)
            } catch (e: IOException) {
                viewModel.keyFileUnreadable(e.message.orEmpty())
            } catch (e: SecurityException) {
                viewModel.keyFileUnreadable(e.message.orEmpty())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        viewModel.start(arguments?.getString(ARG_EDIT_ID))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KonsoleTheme {
                    val form by viewModel.form.collectAsStateWithLifecycle()
                    val message by viewModel.message.collectAsStateWithLifecycle()
                    val keyDialog by viewModel.keyDialog.collectAsStateWithLifecycle()
                    NewConnectionForm(
                        form = form,
                        message = message,
                        keyDialog = keyDialog,
                        errorText = { SshErrorMessages.text(requireContext(), it) },
                        actions = actions(),
                    )
                }
            }
        }

    private fun actions() = NewConnectionActions(
        onName = viewModel::setName,
        onHost = viewModel::setHost,
        onPort = viewModel::setPort,
        onUsername = viewModel::setUsername,
        onPassword = viewModel::setPassword,
        onAuthType = viewModel::setAuthType,
        onJump = viewModel::selectJump,
        onExpandJump = viewModel::expandJump,
        onPickKeyFile = { pickKeyFile.launch(arrayOf("*/*")) },
        onTest = viewModel::test,
        onGenerateKey = viewModel::generateKey,
        onSave = viewModel::save,
        onCancel = ::dismiss,
        onMessageShown = viewModel::onMessageShown,
        onKeyDialogShown = viewModel::onKeyDialogShown,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.prompt.collect(::renderPrompt) }
                launch {
                    viewModel.savedId.collect { id ->
                        id ?: return@collect
                        val openTab = arguments?.getBoolean(ARG_OPEN_TAB) ?: false
                        setFragmentResult(RESULT_KEY, bundleOf(KEY_CONNECTION_ID to id, KEY_OPEN_TAB to openTab))
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The Compose card draws its own background; the window only gives it room and resizes for the keyboard.
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun renderPrompt(prompt: PendingPrompt?) {
        if (prompt?.id == shownPromptId) return
        promptDialog?.dismiss()
        shownPromptId = prompt?.id
        promptDialog = prompt?.let {
            PromptDialogs.show(requireContext(), it) { answer -> viewModel.answerPrompt(it.id, answer) }
        }
    }

    override fun onDestroy() {
        promptDialog?.dismiss()
        promptDialog = null
        super.onDestroy()
    }
}
