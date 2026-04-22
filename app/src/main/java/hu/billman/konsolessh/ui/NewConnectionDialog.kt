package hu.billman.konsolessh.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.gson.Gson
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.DialogNewConnectionBinding
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.SavedConnections

class NewConnectionDialog : DialogFragment() {

    interface Listener {
        fun onConnectionSelected(config: ConnectionConfig)
    }

    companion object {
        private const val ARG_EDIT_JSON = "edit_json"

        /** Open dialog pre-filled for editing an existing connection. */
        fun newForEdit(config: ConnectionConfig): NewConnectionDialog =
            NewConnectionDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_EDIT_JSON, Gson().toJson(config))
                }
            }
    }

    var listener: Listener? = null
    private var _binding: DialogNewConnectionBinding? = null
    private val binding get() = _binding!!

    /** Non-null when editing an existing connection. */
    private var editConfig: ConnectionConfig? = null

    /** The saved connection chosen as jump host (null = manual entry). */
    private var selectedJumpConfig: ConnectionConfig? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val fileName = uri.path?.substringAfterLast('/') ?: ""
            if (fileName.lowercase().endsWith(".pub")) {
                KonsoleToast.show(binding.root, getString(R.string.warning_public_key))
            }

            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                val content = stream.bufferedReader().readText()
                binding.editPrivateKey.setText(content)
                updateKeyStatus(content)
            }
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.error_read_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogNewConnectionBinding.inflate(LayoutInflater.from(requireContext()))

        // Resolve edit mode
        editConfig = arguments?.getString(ARG_EDIT_JSON)
            ?.let { Gson().fromJson(it, ConnectionConfig::class.java) }

        // Always hide the saved-connections spinner — in edit mode we pre-fill
        // the fields directly; in new-connection mode it's just confusing.
        binding.savedConnectionsSection.visibility = View.GONE

        val cfg = editConfig
        if (cfg != null) {
            populateFromConfig(cfg)
        } else {
            val mostUsed = SavedConnections.load(requireContext())
                .groupBy { it.username }
                .maxByOrNull { it.value.size }
                ?.key
            if (!mostUsed.isNullOrBlank()) binding.editUsername.setText(mostUsed)
        }

        setupAuthTypeToggle()
        setupKeyFilePicker()
        setupJumpSpinner()
        
        binding.btnToggleJump.setOnClickListener {
            binding.jumpSection.visibility = View.VISIBLE
            binding.btnToggleJump.visibility = View.GONE
        }

        val title = if (editConfig != null)
            getString(R.string.dialog_edit_connection_title)
        else
            getString(R.string.dialog_new_connection_title)
        val dialog = AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validateAndConnect()) dialog.dismiss()
            }
        }

        return dialog
    }

    private fun updateKeyStatus(content: String) {
        binding.textKeyStatus.visibility = if (content.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun setupJumpSpinner() {
        val options = SavedConnections.load(requireContext()).filter { it.id != editConfig?.id }
        if (options.isEmpty()) {
            binding.spinnerJumpConnection.visibility = View.GONE
            return
        }
        val labels = mutableListOf(getString(R.string.jump_host_none)) + options.map { it.displayName() }
        val adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerJumpConnection.adapter = adapter

        // Pre-select if editing a connection that references a saved jump
        val preselect = editConfig?.jumpConnectionId?.let { jid -> options.indexOfFirst { it.id == jid } } ?: -1
        if (preselect >= 0) {
            binding.spinnerJumpConnection.setSelection(preselect + 1)
            selectedJumpConfig = options[preselect]
        }

        binding.spinnerJumpConnection.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: android.view.View?,
                    position: Int, id: Long
                ) {
                    selectedJumpConfig = if (position == 0) null else options[position - 1]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupKeyFilePicker() {
        binding.btnPickKeyFile.setOnClickListener {
            pickKeyFile.launch(arrayOf("*/*"))
        }
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
        // Spinner preselection is handled in setupJumpSpinner()
    }

    private fun setupAuthTypeToggle() {
        binding.radioGroupAuth.setOnCheckedChangeListener { _, _ -> updateAuthTypeVisibility() }
        binding.editHost.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val host = s?.toString()?.trim() ?: ""
                val internal = isPrivatePrefix(host)
                updateHostLabels(internal)
                updateJumpVisibility(internal, selectedJumpConfig != null)
            }
        })
    }

    private fun updateJumpVisibility(isInternal: Boolean, hasJump: Boolean) {
        if (isInternal) {
            binding.jumpSection.visibility = View.VISIBLE
            binding.btnToggleJump.visibility = View.GONE
        } else {
            if (hasJump) {
                binding.jumpSection.visibility = View.VISIBLE
                binding.btnToggleJump.visibility = View.GONE
            } else {
                binding.jumpSection.visibility = View.GONE
                binding.btnToggleJump.visibility = View.VISIBLE
            }
        }
    }

    private fun updateHostLabels(isInternal: Boolean) {
        binding.layoutHost.hint = getString(
            if (isInternal) R.string.host_internal_hint else R.string.host_hint
        )
        binding.layoutPort.hint = getString(
            if (isInternal) R.string.port_internal_hint else R.string.port_hint
        )
    }

    /** True as soon as the first octet reveals a private range (10/172/192). */
    private fun isPrivatePrefix(host: String): Boolean {
        if (!host.contains('.')) return false
        return when (host.substringBefore('.').toIntOrNull()) {
            10, 172, 192 -> true
            else -> false
        }
    }

    private fun updateAuthTypeVisibility() {
        val useKey = binding.radioPrivateKey.isChecked
        binding.layoutPassword.visibility   = if (useKey) View.GONE else View.VISIBLE
        binding.layoutPrivateKey.visibility = if (useKey) View.VISIBLE else View.GONE
    }

    private fun validateAndConnect(): Boolean {
        val host     = binding.editHost.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()
        if (host.isEmpty())     { KonsoleToast.show(binding.root, getString(R.string.error_host_required)); return false }
        if (username.isEmpty()) { KonsoleToast.show(binding.root, getString(R.string.error_username_required)); return false }

        val port     = binding.editPort.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: 22
        val authType = if (binding.radioPrivateKey.isChecked)
            ConnectionConfig.AuthType.PRIVATE_KEY else ConnectionConfig.AuthType.PASSWORD

        val config = ConnectionConfig(
            id       = editConfig?.id ?: java.util.UUID.randomUUID().toString(),
            name     = binding.editName.text.toString().trim(),
            host     = host, port = port, username = username,
            authType = authType,
            password = binding.editPassword.text.toString(),
            privateKey       = binding.editPrivateKey.text.toString().trim(),
            jumpConnectionId = selectedJumpConfig?.id ?: ""
        )

        SavedConnections.saveOne(requireContext(), config)

        listener?.onConnectionSelected(config)
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
