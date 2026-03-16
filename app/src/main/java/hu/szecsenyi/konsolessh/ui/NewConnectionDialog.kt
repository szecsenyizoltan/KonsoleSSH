package hu.szecsenyi.konsolessh.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.gson.Gson
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.DialogNewConnectionBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections

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
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                _binding?.editPrivateKey?.setText(stream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Nem sikerült beolvasni: ${e.message}", Toast.LENGTH_SHORT).show()
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

        if (editConfig != null) {
            populateFromConfig(editConfig!!)
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

        val title = if (editConfig != null) "Kapcsolat szerkesztése" else "Új SSH kapcsolat"
        val dialog = AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(title)
            .setView(binding.root)
            .setPositiveButton("Mentés", null)
            .setNegativeButton("Mégse", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validateAndConnect()) dialog.dismiss()
            }
        }

        return dialog
    }

    private fun setupJumpSpinner() {
        val options = SavedConnections.load(requireContext()).filter { it.id != editConfig?.id }
        if (options.isEmpty()) {
            binding.spinnerJumpConnection.visibility = View.GONE
            return
        }
        val labels = mutableListOf("Mentett kapcsolatból…") + options.map { it.displayName() }
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
        updateAuthTypeVisibility()
        binding.jumpSection.visibility = if (config.hasJump() || isInternal) View.VISIBLE else View.GONE
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
                binding.jumpSection.visibility = if (internal) View.VISIBLE else View.GONE
            }
        })
    }

    private fun updateHostLabels(isInternal: Boolean) {
        binding.layoutHost.hint = if (isInternal) "Belső host (IP vagy hostname) *" else "Host (IP vagy hostname) *"
        binding.layoutPort.hint = if (isInternal) "Belső port" else "Port"
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
        if (host.isEmpty())     { KonsoleToast.show(binding.root, "Szerver cím szükséges"); return false }
        if (username.isEmpty()) { KonsoleToast.show(binding.root, "Felhasználónév szükséges"); return false }

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
