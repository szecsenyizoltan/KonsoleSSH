package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.ActivityConnectionEditBinding
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.launch

/**
 * A mentett kapcsolatok kezelése. A lista a [ConnectionsViewModel]
 * állapotából renderelődik; mentést csak a [NewConnectionDialog] végez.
 */
class ConnectionEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionEditBinding
    private val viewModel: ConnectionsViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarAndImePadding()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manage_connections)

        binding.btnAdd.setOnClickListener {
            NewConnectionDialog.newForCreate(openTab = false).show(supportFragmentManager, DIALOG_TAG)
        }
        supportFragmentManager.setFragmentResultListener(NewConnectionDialog.RESULT_KEY, this) { _, _ ->
            viewModel.refresh()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderList(state.connections)
                    if (state.storeError) KonsoleToast.show(binding.root, getString(R.string.err_store_unreadable))
                }
            }
        }
    }

    private fun renderList(saved: List<ConnectionConfig>) {
        binding.containerConnections.removeAllViews()
        binding.textEmpty.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
        saved.forEach { config ->
            val itemView = layoutInflater.inflate(R.layout.item_saved_connection, binding.containerConnections, false)
            itemView.findViewById<TextView>(R.id.textConnectionName).text = config.displayName()
            itemView.findViewById<TextView>(R.id.textConnectionDetails).text =
                getString(R.string.connection_details, config.username, config.host, config.port)
            itemView.findViewById<View>(R.id.btnEdit).setOnClickListener {
                NewConnectionDialog.newForEdit(config.id).show(supportFragmentManager, DIALOG_TAG)
            }
            itemView.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete(config) }
            binding.containerConnections.addView(itemView)
        }
    }

    private fun confirmDelete(config: ConnectionConfig) {
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setMessage(getString(R.string.delete_connection_confirm, config.displayName()))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.delete(config.id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val DIALOG_TAG = "connection_dialog"
    }
}
