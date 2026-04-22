package hu.szecsenyi.konsolessh.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import hu.szecsenyi.konsolessh.databinding.ActivityConnectionEditBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections

/**
 * Full-screen activity for managing saved SSH connection profiles.
 */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(hu.szecsenyi.konsolessh.R.string.manage_connections)

        refreshList()

        binding.btnAdd.setOnClickListener { showAddDialog() }
    }

    private fun refreshList() {
        val saved = SavedConnections.load(this)
        binding.containerConnections.removeAllViews()
        if (saved.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            return
        }
        binding.textEmpty.visibility = View.GONE
        saved.forEach { config ->
            val itemView = layoutInflater.inflate(
                hu.szecsenyi.konsolessh.R.layout.item_saved_connection,
                binding.containerConnections,
                false
            )
            itemView.findViewById<android.widget.TextView>(hu.szecsenyi.konsolessh.R.id.textConnectionName)
                .text = config.displayName()
            itemView.findViewById<android.widget.TextView>(hu.szecsenyi.konsolessh.R.id.textConnectionDetails)
                .text = "${config.username}@${config.host}:${config.port}"
            itemView.findViewById<View>(hu.szecsenyi.konsolessh.R.id.btnEdit).setOnClickListener {
                showEditDialog(config)
            }
            itemView.findViewById<View>(hu.szecsenyi.konsolessh.R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this, hu.szecsenyi.konsolessh.R.style.KonsoleDialog)
                    .setMessage(getString(hu.szecsenyi.konsolessh.R.string.delete_connection_confirm, config.displayName()))
                    .setPositiveButton(hu.szecsenyi.konsolessh.R.string.action_delete) { _, _ ->
                        SavedConnections.delete(this, config.id)
                        refreshList()
                    }
                    .setNegativeButton(hu.szecsenyi.konsolessh.R.string.action_cancel, null)
                    .show()
            }
            binding.containerConnections.addView(itemView)
        }
    }

    private fun showAddDialog() {
        val dialog = NewConnectionDialog()
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) {
                SavedConnections.saveOne(this@ConnectionEditActivity, config)
                refreshList()
            }
        }
        dialog.show(supportFragmentManager, "add_connection")
    }

    private fun showEditDialog(config: ConnectionConfig) {
        val dialog = NewConnectionDialog.newForEdit(config)
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) {
                refreshList()
            }
        }
        dialog.show(supportFragmentManager, "edit_connection")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
