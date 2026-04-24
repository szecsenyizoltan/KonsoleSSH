package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.AppContainer
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.ActivityConnectionEditBinding
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.launch

/**
 * Full-screen activity for managing saved SSH connection profiles.
 * Lista-forrás: [ConnectionsViewModel] StateFlow-ja; a UI reaktívan
 * követi a repository változásait.
 */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding
    private lateinit var viewModel: ConnectionsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            KonsoleViewModelFactory(AppContainer.from(this)),
        )[ConnectionsViewModel::class.java]

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom),
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manage_connections)

        binding.btnAdd.setOnClickListener { showAddDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.connections.collect { list -> renderList(list) }
            }
        }
    }

    private fun renderList(saved: List<ConnectionConfig>) {
        binding.containerConnections.removeAllViews()
        if (saved.isEmpty()) {
            binding.textEmpty.visibility = View.VISIBLE
            return
        }
        binding.textEmpty.visibility = View.GONE
        saved.forEach { config ->
            val itemView = layoutInflater.inflate(
                R.layout.item_saved_connection,
                binding.containerConnections,
                false,
            )
            itemView.findViewById<android.widget.TextView>(R.id.textConnectionName)
                .text = config.displayName()
            itemView.findViewById<android.widget.TextView>(R.id.textConnectionDetails)
                .text = "${config.username}@${config.host}:${config.port}"
            itemView.findViewById<View>(R.id.btnEdit).setOnClickListener {
                showEditDialog(config)
            }
            itemView.findViewById<View>(R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this, R.style.KonsoleDialog)
                    .setMessage(getString(R.string.delete_connection_confirm, config.displayName()))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        viewModel.delete(config.id)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            binding.containerConnections.addView(itemView)
        }
    }

    private fun showAddDialog() {
        val dialog = NewConnectionDialog()
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) {
                viewModel.saveOne(config)
            }
        }
        dialog.show(supportFragmentManager, "add_connection")
    }

    private fun showEditDialog(config: ConnectionConfig) {
        val dialog = NewConnectionDialog.newForEdit(config)
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) {
                // A dialog maga menti (backward-compat), de a
                // ViewModel-nek frissítenie kell a listát.
                viewModel.refresh()
            }
        }
        dialog.show(supportFragmentManager, "edit_connection")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
