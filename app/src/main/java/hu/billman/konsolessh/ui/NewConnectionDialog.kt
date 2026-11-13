package hu.billman.konsolessh.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.DialogNewConnectionBinding
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.ssh.SshError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Új kapcsolat létrehozása vagy egy mentett szerkesztése. Csak megjelenít és
 * a [NewConnectionViewModel]-nek ad parancsot; a mentés eredménye a Fragment
 * Result API-n megy vissza ([RESULT_KEY]), így forgatás után is megérkezik.
 * Szerkesztéskor csak az azonosítót kapja, a titkokat a repository-ból tölti.
 */
class NewConnectionDialog : DialogFragment() {

    companion object {
        const val RESULT_KEY = "new_connection_result"
        const val KEY_CONNECTION_ID = "connection_id"
        const val KEY_OPEN_TAB = "open_tab"
        private const val ARG_EDIT_ID = "edit_id"
        private const val ARG_OPEN_TAB = "open_tab"

        /** Egy privát kulcs pár KB; ennél nagyobb fájl biztosan nem az. */
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
        private const val PUBLIC_KEY_TEXT_SP = 12f

        /** Új kapcsolat; [openTab] esetén mentés után fület is nyit. */
        fun newForCreate(openTab: Boolean) = NewConnectionDialog().apply {
            arguments = bundleOf(ARG_OPEN_TAB to openTab)
        }

        fun newForEdit(connectionId: String) = NewConnectionDialog().apply {
            arguments = bundleOf(ARG_EDIT_ID to connectionId)
        }
    }

    private var _binding: DialogNewConnectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NewConnectionViewModel by viewModels { KonsoleViewModelFactory.from(requireContext()) }

    private val editId: String? get() = arguments?.getString(ARG_EDIT_ID)

    private var promptDialog: Dialog? = null
    private var shownPromptId: Long? = null

    /** The jump options the spinner was built for; rebuilt only when they change. */
    private var spinnerOptions: List<NewConnectionViewModel.JumpOption>? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        if (uri.path.orEmpty().lowercase().endsWith(".pub")) {
            KonsoleToast.show(binding.root, getString(R.string.warning_public_key))
        }
        // A document provider (e.g. a cloud drive) may block: read off the main thread.
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            val content = try {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readAtMost(MAX_KEY_FILE_BYTES + 1)
                        if (bytes.size > MAX_KEY_FILE_BYTES) throw IOException("file is too large for a key")
                        String(bytes, Charsets.UTF_8)
                    }
                }
            } catch (e: IOException) {
                showToast(getString(R.string.error_read_file, e.message.orEmpty()))
                null
            } catch (e: SecurityException) {
                showToast(getString(R.string.error_read_file, e.message.orEmpty()))
                null
            }
            if (content != null) viewModel.setPrivateKey(content)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogNewConnectionBinding.inflate(LayoutInflater.from(requireContext()))
        // The saved-connections spinner is not used: edit mode fills the fields directly.
        binding.savedConnectionsSection.visibility = View.GONE
        viewModel.start(editId)
        bindInputs()

        val dialog = AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(
                if (editId != null) R.string.dialog_edit_connection_title else R.string.dialog_new_connection_title,
            )
            .setView(binding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { viewModel.save() }
        }
        observeViewModel()
        return dialog
    }

    /** Every user edit goes to the ViewModel; the views never hold state of their own. */
    private fun bindInputs() = with(binding) {
        editName.addTextChangedListener(afterTextChanged(viewModel::setName))
        editHost.addTextChangedListener(afterTextChanged(viewModel::setHost))
        editPort.addTextChangedListener(afterTextChanged(viewModel::setPort))
        editUsername.addTextChangedListener(afterTextChanged(viewModel::setUsername))
        editPassword.addTextChangedListener(afterTextChanged(viewModel::setPassword))
        editPrivateKey.addTextChangedListener(afterTextChanged(viewModel::setPrivateKey))
        radioGroupAuth.setOnCheckedChangeListener { _, checkedId ->
            val authType = when (checkedId) {
                R.id.radioPrivateKey -> ConnectionConfig.AuthType.PRIVATE_KEY
                else -> ConnectionConfig.AuthType.PASSWORD
            }
            viewModel.setAuthType(authType)
        }
        btnPickKeyFile.setOnClickListener { pickKeyFile.launch(arrayOf("*/*")) }
        btnGenerateKey.setOnClickListener { viewModel.generateKey() }
        btnTestConnection.setOnClickListener { viewModel.test() }
        btnToggleJump.setOnClickListener { viewModel.expandJump() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.form.collect(::render) }
                launch { viewModel.prompt.collect(::renderPrompt) }
                launch {
                    viewModel.message.collect { message ->
                        message ?: return@collect
                        showToast(messageText(message))
                        viewModel.onMessageShown()
                    }
                }
                launch {
                    viewModel.keyDialog.collect { keyDialog ->
                        keyDialog ?: return@collect
                        when (keyDialog) {
                            is NewConnectionViewModel.KeyDialog.Uploaded ->
                                showUploadSuccessDialog(keyDialog.publicLine)
                            is NewConnectionViewModel.KeyDialog.ManualFallback ->
                                showManualFallbackDialog(keyDialog.publicLine)
                        }
                        viewModel.onKeyDialogShown()
                    }
                }
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

    private fun render(form: NewConnectionViewModel.FormState) = with(binding) {
        editName.showText(form.name)
        editHost.showText(form.host)
        editPort.showText(form.port)
        editUsername.showText(form.username)
        editPassword.showText(form.password)
        editPrivateKey.showText(form.privateKey)
        val radio = if (form.usesKey) radioPrivateKey else radioPassword
        if (!radio.isChecked) radio.isChecked = true

        layoutPassword.visibility = if (form.usesKey) View.GONE else View.VISIBLE
        layoutPrivateKey.visibility = if (form.usesKey) View.VISIBLE else View.GONE
        textKeyStatus.visibility = if (form.hasKey) View.VISIBLE else View.GONE
        layoutHost.hint = getString(if (form.isInternalHost) R.string.host_internal_hint else R.string.host_hint)
        layoutPort.hint = getString(if (form.isInternalHost) R.string.port_internal_hint else R.string.port_hint)
        jumpSection.visibility = if (form.showJump) View.VISIBLE else View.GONE
        btnToggleJump.visibility = if (form.showJump) View.GONE else View.VISIBLE
        renderJumpSpinner(form)

        textTestStatus.text = statusText(form.status)
        btnTestConnection.isEnabled = !form.busy
        btnGenerateKey.isEnabled = form.canGenerateKey
    }

    /** Sets text only when it differs, so the cursor stays where the user is typing. */
    private fun EditText.showText(value: String) {
        if (text.toString() != value) setText(value)
    }

    private fun renderJumpSpinner(form: NewConnectionViewModel.FormState) {
        val spinner = binding.spinnerJumpConnection
        val options = form.jumpOptions
        spinner.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
        if (options != spinnerOptions) {
            spinnerOptions = options
            val labels = listOf(getString(R.string.jump_host_none)) + options.map { it.label }
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    viewModel.selectJump(if (position == 0) null else options[position - 1].id)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        val position = options.indexOfFirst { it.id == form.jumpId } + 1
        if (spinner.selectedItemPosition != position) spinner.setSelection(position)
    }

    private fun statusText(status: NewConnectionViewModel.Status): String = when (status) {
        NewConnectionViewModel.Status.None -> ""
        NewConnectionViewModel.Status.Testing -> getString(R.string.test_running)
        NewConnectionViewModel.Status.Uploading -> getString(R.string.upload_running)
        NewConnectionViewModel.Status.StoreUnreadable -> errorText(SshError.StoreUnreadable)
        is NewConnectionViewModel.Status.TestSucceeded ->
            getString(R.string.test_success, status.serverVersion.ifBlank { "?" })
        is NewConnectionViewModel.Status.TestFailed -> getString(R.string.test_failure, errorText(status.error))
        is NewConnectionViewModel.Status.Invalid -> getString(
            when (status.reason) {
                NewConnectionViewModel.InvalidReason.NEEDS_HOST_AND_USER -> R.string.test_needs_host_user
                NewConnectionViewModel.InvalidReason.NEEDS_PRIVATE_KEY -> R.string.test_needs_private_key
                NewConnectionViewModel.InvalidReason.NEEDS_PASSWORD -> R.string.test_needs_password
            },
        )
        is NewConnectionViewModel.Status.UploadSucceeded ->
            getString(R.string.upload_success, status.serverType.name.lowercase())
        is NewConnectionViewModel.Status.UploadFailed -> getString(R.string.upload_failure, errorText(status.error))
    }

    private fun messageText(message: NewConnectionViewModel.Message): String = when (message) {
        NewConnectionViewModel.Message.HostRequired -> getString(R.string.error_host_required)
        NewConnectionViewModel.Message.UsernameRequired -> getString(R.string.error_username_required)
        NewConnectionViewModel.Message.StoreWriteFailed -> getString(R.string.err_store_write)
        NewConnectionViewModel.Message.NeedsTest -> getString(R.string.generate_needs_test)
        is NewConnectionViewModel.Message.KeyGenerationFailed ->
            getString(R.string.error_generic, errorText(message.error))
    }

    private fun showToast(text: String) {
        _binding?.let { KonsoleToast.show(it.root, text) }
    }

    private fun renderPrompt(prompt: PendingPrompt?) {
        if (prompt?.id == shownPromptId) return
        promptDialog?.dismiss()
        shownPromptId = prompt?.id
        promptDialog = prompt?.let {
            PromptDialogs.show(requireContext(), it) { answer -> viewModel.answerPrompt(it.id, answer) }
        }
    }

    private fun errorText(error: SshError): String = SshErrorMessages.text(requireContext(), error)

    private fun showUploadSuccessDialog(publicKeyLine: String) {
        AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(R.string.key_generated_title)
            .setMessage(getString(R.string.upload_success_message, publicKeyLine))
            .setPositiveButton(R.string.action_ok, null)
            .setCancelable(false)
            .show()
    }

    private fun showManualFallbackDialog(publicKeyLine: String) {
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val keyView = EditText(requireContext()).apply {
            setText(publicKeyLine)
            isFocusable = false
            isCursorVisible = false
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            setSingleLine(false)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(padding, padding / 2, padding, padding / 2)
            textSize = PUBLIC_KEY_TEXT_SP
            typeface = Typeface.MONOSPACE
        }
        AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(R.string.upload_manual_title)
            .setMessage(R.string.upload_manual_message)
            .setView(keyView)
            .setPositiveButton(R.string.action_copy_public_key) { _, _ ->
                requireContext().getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText("ssh-public-key", publicKeyLine))
                showToast(getString(R.string.key_copied))
            }
            .setNegativeButton(R.string.action_close, null)
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        promptDialog?.dismiss()
        promptDialog = null
        _binding = null
        super.onDestroy()
    }

    private fun afterTextChanged(action: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action(s?.toString().orEmpty())
    }
}
