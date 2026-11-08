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
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.databinding.DialogNewConnectionBinding
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

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
        private const val MAX_PORT = 65535
        private const val DEFAULT_PORT = 22

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

    private var jumpOptions: List<ConnectionConfig> = emptyList()

    /** Snapshot of (host, port, username, password) at the last successful test. */
    private var testedCredSnapshot: String? = null

    /** Server type detected during the last successful test — informs the upload strategy. */
    private var testedServerType: SshKeyProvisioner.ServerType = SshKeyProvisioner.ServerType.UNKNOWN

    private var promptDialog: Dialog? = null
    private var shownPromptId: Long? = null

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
                KonsoleToast.show(binding.root, getString(R.string.error_read_file, e.message.orEmpty()))
                null
            } catch (e: SecurityException) {
                KonsoleToast.show(binding.root, getString(R.string.error_read_file, e.message.orEmpty()))
                null
            }
            if (content != null && _binding != null) {
                binding.editPrivateKey.setText(content)
                updateKeyStatus(content)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogNewConnectionBinding.inflate(LayoutInflater.from(requireContext()))
        // The saved-connections spinner is not used: edit mode fills the fields directly.
        binding.savedConnectionsSection.visibility = View.GONE

        setupAuthTypeToggle()
        binding.btnPickKeyFile.setOnClickListener { pickKeyFile.launch(arrayOf("*/*")) }
        binding.btnGenerateKey.setOnClickListener { onGenerateKeyClicked() }
        binding.btnTestConnection.setOnClickListener { onTestClicked() }
        binding.btnToggleJump.setOnClickListener {
            binding.jumpSection.visibility = View.VISIBLE
            binding.btnToggleJump.visibility = View.GONE
        }
        invalidateTestOnCredChange()

        val dialog = AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(
                if (editId != null) R.string.dialog_edit_connection_title else R.string.dialog_new_connection_title,
            )
            .setView(binding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onSaveClicked() }
        }

        // After rotation the views restore the user's edits; only fill them once.
        loadInitialData(fillFields = savedInstanceState == null)
        observeViewModel()
        return dialog
    }

    private fun loadInitialData(fillFields: Boolean) {
        lifecycleScope.launch {
            try {
                val id = editId
                if (id != null) {
                    val config = viewModel.loadForEdit(id)
                    if (config != null && fillFields) populateFromConfig(config)
                } else if (fillFields) {
                    viewModel.mostUsedUsername()?.takeIf { it.isNotBlank() }?.let { binding.editUsername.setText(it) }
                }
                setupJumpSpinner(viewModel.jumpOptions())
            } catch (e: ConnectionStoreException) {
                setTestStatus(SshErrorMessages.text(requireContext(), SshError.StoreUnreadable), successful = false)
            }
        }
    }

    // Test / key upload results and their host key questions arrive as state,
    // so they survive the dialog being recreated.
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.prompt.collect(::renderPrompt) }
                launch {
                    viewModel.busy.collect { busy ->
                        binding.btnTestConnection.isEnabled = !busy
                        if (busy) binding.btnGenerateKey.isEnabled = false
                    }
                }
                launch {
                    viewModel.testResult.collect { result ->
                        result ?: return@collect
                        renderTestResult(result)
                        viewModel.onTestResultShown()
                    }
                }
                launch {
                    viewModel.uploadResult.collect { result ->
                        result ?: return@collect
                        renderUploadResult(result)
                        viewModel.onUploadResultShown()
                    }
                }
            }
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

    private fun updateKeyStatus(content: String) {
        binding.textKeyStatus.visibility = if (content.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun setupJumpSpinner(options: List<ConnectionConfig>) {
        jumpOptions = options
        if (options.isEmpty()) {
            binding.spinnerJumpConnection.visibility = View.GONE
            return
        }
        val labels = listOf(getString(R.string.jump_host_none)) + options.map { it.displayName() }
        binding.spinnerJumpConnection.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            labels,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val preselect = options.indexOfFirst { it.id == viewModel.selectedJumpId }
        if (preselect >= 0) {
            binding.spinnerJumpConnection.setSelection(preselect + 1)
            binding.jumpSection.visibility = View.VISIBLE
            binding.btnToggleJump.visibility = View.GONE
        }
        binding.spinnerJumpConnection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewModel.selectedJumpId = if (position == 0) null else options[position - 1].id
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Any change in host/port/user/password invalidates the prior test. */
    private fun invalidateTestOnCredChange() {
        val watcher = afterTextChanged {
            if (testedCredSnapshot != null && testedCredSnapshot != currentCredSnapshot()) {
                setTestStatus(null, successful = false)
            }
        }
        listOf(binding.editHost, binding.editPort, binding.editUsername, binding.editPassword)
            .forEach { it.addTextChangedListener(watcher) }
    }

    private fun currentCredSnapshot(): String = listOf(
        binding.editHost.text.toString().trim(),
        binding.editPort.text.toString().trim(),
        binding.editUsername.text.toString().trim(),
        binding.editPassword.text.toString(),
    ).joinToString("\u0000")

    private fun setTestStatus(message: String?, successful: Boolean) {
        binding.textTestStatus.text = message.orEmpty()
        testedCredSnapshot = if (successful) currentCredSnapshot() else null
        binding.btnGenerateKey.isEnabled = successful
    }

    private fun port(): Int = binding.editPort.text.toString().toIntOrNull()?.coerceIn(1, MAX_PORT) ?: DEFAULT_PORT

    /** A dialógus mezőiből összeállított konfig (azonosító: szerkesztéskor a meglévő). */
    private fun formConfig(useKey: Boolean = binding.radioPrivateKey.isChecked) = ConnectionConfig(
        id = editId ?: viewModel.editConfig?.id ?: UUID.randomUUID().toString(),
        name = binding.editName.text.toString().trim(),
        host = binding.editHost.text.toString().trim(),
        port = port(),
        username = binding.editUsername.text.toString().trim(),
        authType = if (useKey) ConnectionConfig.AuthType.PRIVATE_KEY else ConnectionConfig.AuthType.PASSWORD,
        password = binding.editPassword.text.toString(),
        privateKey = binding.editPrivateKey.text.toString().trim(),
        jumpConnectionId = viewModel.selectedJumpId.orEmpty(),
    )

    private fun onTestClicked() {
        val form = formConfig()
        val useKey = binding.radioPrivateKey.isChecked
        val validationError = when {
            form.host.isEmpty() || form.username.isEmpty() -> R.string.test_needs_host_user
            useKey && form.privateKey.isEmpty() -> R.string.test_needs_private_key
            !useKey && form.password.isEmpty() -> R.string.test_needs_password
            else -> null
        }
        if (validationError != null) {
            setTestStatus(getString(validationError), successful = false)
            return
        }
        // Test only the credentials of the selected auth mode.
        val probe = if (useKey) form.copy(password = "") else form.copy(privateKey = "")
        binding.textTestStatus.text = getString(R.string.test_running)
        viewModel.startTest(probe)
    }

    private fun renderTestResult(result: NewConnectionViewModel.TestResult) {
        when (result) {
            is NewConnectionViewModel.TestResult.Success -> {
                testedServerType = result.serverType
                // Key generation needs a password-based test: the upload logs in with it.
                val banner = result.serverVersion.ifBlank { "?" }
                setTestStatus(getString(R.string.test_success, banner), successful = !binding.radioPrivateKey.isChecked)
            }
            is NewConnectionViewModel.TestResult.Failure ->
                setTestStatus(getString(R.string.test_failure, errorText(result.error)), successful = false)
        }
    }

    private fun errorText(error: SshError): String = SshErrorMessages.text(requireContext(), error)

    private fun onGenerateKeyClicked() {
        if (testedCredSnapshot == null || testedCredSnapshot != currentCredSnapshot()) {
            KonsoleToast.show(binding.root, getString(R.string.generate_needs_test))
            return
        }
        val uploadConfig = formConfig(useKey = false).copy(privateKey = "")
        binding.textTestStatus.text = getString(R.string.upload_running)
        viewModel.startKeyUpload(uploadConfig)
    }

    private fun renderUploadResult(result: NewConnectionViewModel.UploadResult) {
        when (result) {
            is NewConnectionViewModel.UploadResult.KeyGenerationFailure -> {
                binding.btnGenerateKey.isEnabled = true
                KonsoleToast.show(binding.root, getString(R.string.error_generic, errorText(result.error)))
            }
            is NewConnectionViewModel.UploadResult.Success -> {
                binding.radioPrivateKey.isChecked = true
                updateAuthTypeVisibility()
                binding.editPrivateKey.setText(result.privatePem)
                binding.editPassword.setText("")
                updateKeyStatus(result.privatePem)
                // The password was cleared, so the earlier test no longer applies.
                val serverType = result.serverType.name.lowercase()
                setTestStatus(getString(R.string.upload_success, serverType), successful = false)
                showUploadSuccessDialog(result.publicLine)
            }
            is NewConnectionViewModel.UploadResult.UploadFailure -> {
                // Keep the green test state so the user can retry.
                setTestStatus(getString(R.string.upload_failure, errorText(result.error)), successful = true)
                showManualFallbackDialog(result.publicLine)
            }
        }
    }

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
                KonsoleToast.show(binding.root, getString(R.string.key_copied))
            }
            .setNegativeButton(R.string.action_close, null)
            .setCancelable(false)
            .show()
    }

    private fun populateFromConfig(config: ConnectionConfig) {
        binding.editName.setText(config.name)
        val isInternal = isPrivatePrefix(config.host)
        updateHostLabels(isInternal)
        binding.editHost.setText(config.host)
        binding.editPort.setText(config.port.toString())
        binding.editUsername.setText(config.username)
        binding.editPassword.setText(config.password)
        if (config.authType == ConnectionConfig.AuthType.PRIVATE_KEY) {
            binding.radioPrivateKey.isChecked = true
        } else {
            binding.radioPassword.isChecked = true
        }
        binding.editPrivateKey.setText(config.privateKey)
        updateKeyStatus(config.privateKey)
        updateAuthTypeVisibility()
        updateJumpVisibility(isInternal, config.hasJump())
    }

    private fun setupAuthTypeToggle() {
        binding.radioGroupAuth.setOnCheckedChangeListener { _, _ -> updateAuthTypeVisibility() }
        binding.editHost.addTextChangedListener(
            afterTextChanged { text ->
                val internal = isPrivatePrefix(text.trim())
                updateHostLabels(internal)
                updateJumpVisibility(internal, viewModel.selectedJumpId != null)
            },
        )
    }

    private fun updateJumpVisibility(isInternal: Boolean, hasJump: Boolean) {
        val show = isInternal || hasJump
        binding.jumpSection.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnToggleJump.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun updateHostLabels(isInternal: Boolean) {
        binding.layoutHost.hint = getString(if (isInternal) R.string.host_internal_hint else R.string.host_hint)
        binding.layoutPort.hint = getString(if (isInternal) R.string.port_internal_hint else R.string.port_hint)
    }

    /** True as soon as the first octet reveals a private range (10/172/192). */
    private fun isPrivatePrefix(host: String): Boolean {
        if (!host.contains('.')) return false
        return host.substringBefore('.').toIntOrNull() in PRIVATE_FIRST_OCTETS
    }

    private fun updateAuthTypeVisibility() {
        val useKey = binding.radioPrivateKey.isChecked
        binding.layoutPassword.visibility = if (useKey) View.GONE else View.VISIBLE
        binding.layoutPrivateKey.visibility = if (useKey) View.VISIBLE else View.GONE
    }

    private fun onSaveClicked() {
        val form = formConfig()
        when {
            form.host.isEmpty() -> KonsoleToast.show(binding.root, getString(R.string.error_host_required))
            form.username.isEmpty() -> KonsoleToast.show(binding.root, getString(R.string.error_username_required))
            else -> lifecycleScope.launch {
                if (viewModel.save(form)) {
                    val openTab = arguments?.getBoolean(ARG_OPEN_TAB) ?: false
                    setFragmentResult(RESULT_KEY, bundleOf(KEY_CONNECTION_ID to form.id, KEY_OPEN_TAB to openTab))
                    dismiss()
                } else {
                    KonsoleToast.show(binding.root, getString(R.string.err_store_write))
                }
            }
        }
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

private val PRIVATE_FIRST_OCTETS = setOf(10, 172, 192)
