package hu.szecsenyi.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.DialogTabPickerBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections

class TabPickerSheet : BottomSheetDialogFragment() {

    data class TabEntry(
        val title: String,
        val isActive: Boolean,
        val host: String?,          // null for welcome/local tabs
        val status: ConnectionStatus = ConnectionStatus.NONE
    )

    interface Listener {
        fun onTabSelected(position: Int)
        fun onSavedConnectionSelected(config: ConnectionConfig)
        fun onNewConnectionRequested()
        fun onEditConnectionRequested(config: ConnectionConfig)
        fun onDeleteConnectionRequested(config: ConnectionConfig)
    }

    var listener: Listener? = null
    var openTabs: List<TabEntry> = emptyList()

    private var _binding: DialogTabPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogTabPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildOpenTabs()
        buildSavedConnections()
        binding.btnNewConnection.setOnClickListener {
            dismiss()
            listener?.onNewConnectionRequested()
        }
    }

    private fun buildOpenTabs() {
        if (openTabs.isEmpty()) {
            binding.labelOpenTabs.visibility = View.GONE
            return
        }
        openTabs.forEachIndexed { index, entry ->
            val row = inflateRow()
            val icon = row.findViewById<TextView>(R.id.itemIcon)
            icon.text = if (entry.isActive) "▶" else "●"
            icon.setTextColor(when (entry.status) {
                ConnectionStatus.CONNECTED    -> android.graphics.Color.rgb(63, 185, 80)
                ConnectionStatus.CONNECTING   -> android.graphics.Color.rgb(240, 180, 0)
                ConnectionStatus.DISCONNECTED -> android.graphics.Color.rgb(248, 81, 73)
                ConnectionStatus.NONE         -> android.graphics.Color.GRAY
            })
            row.findViewById<TextView>(R.id.itemTitle).text = entry.title

            // Show hostname below title if available
            if (!entry.host.isNullOrBlank()) {
                row.findViewById<TextView>(R.id.itemSubtitle).apply {
                    text = entry.host
                    visibility = View.VISIBLE
                }
            }

            if (entry.isActive) {
                row.findViewById<TextView>(R.id.itemBadge).apply {
                    text = "aktív"
                    setTextColor(android.graphics.Color.WHITE)
                    visibility = View.VISIBLE
                }
            }

            row.setOnClickListener {
                dismiss()
                listener?.onTabSelected(index)
            }
            binding.containerTabs.addView(row)
        }
    }

    private fun buildSavedConnections() {
        val saved = SavedConnections.load(requireContext())
        if (saved.isEmpty()) {
            binding.labelSaved.visibility = View.GONE
            return
        }
        saved.forEach { config ->
            val row = inflateRow()
            row.findViewById<TextView>(R.id.itemIcon).text = "⚡"
            row.findViewById<TextView>(R.id.itemTitle).text = config.displayName()
            row.findViewById<TextView>(R.id.itemSubtitle).apply {
                text = "${config.username}@${config.host}:${config.port}"
                visibility = View.VISIBLE
            }
            row.findViewById<ImageButton>(R.id.btnEditRow).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    dismiss()
                    listener?.onEditConnectionRequested(config)
                }
            }
            row.findViewById<ImageButton>(R.id.btnDeleteRow).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(requireContext(), hu.szecsenyi.konsolessh.R.style.KonsoleDialog)
                        .setMessage("Törlöd a \"${config.displayName()}\" kapcsolatot?")
                        .setPositiveButton("Törlés") { _, _ ->
                            SavedConnections.delete(requireContext(), config.id)
                            listener?.onDeleteConnectionRequested(config)
                            binding.containerSaved.removeAllViews()
                            buildSavedConnections()
                        }
                        .setNegativeButton("Mégse", null)
                        .show()
                }
            }
            row.setOnClickListener {
                dismiss()
                listener?.onSavedConnectionSelected(config)
            }
            binding.containerSaved.addView(row)
        }
    }

    private fun inflateRow(): View =
        LayoutInflater.from(requireContext())
            .inflate(R.layout.item_picker_row, binding.containerTabs.parent as ViewGroup, false)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
