package hu.billman.konsolessh.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import hu.billman.konsolessh.ui.theme.Monospace

/**
 * The saved connections, with add, edit and delete. Stateless: it draws
 * [state] and reports what the user did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    state: ConnectionsViewModel.UiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ConnectionConfig) -> Unit,
    onDelete: (ConnectionConfig) -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val storeError = stringResource(R.string.err_store_unreadable)
    LaunchedEffect(state.storeError) {
        if (state.storeError) snackbar.showSnackbar(storeError)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_connections)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KonsoleColors.Toolbar),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = KonsoleColors.Accent) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.content_desc_add_connection),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.connections.isEmpty()) {
            Text(
                text = stringResource(R.string.no_saved_connections),
                color = KonsoleColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(padding).padding(48.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + FAB_CLEARANCE,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.connections, key = { it.id }) { config ->
                    ConnectionRow(
                        config = config,
                        onEdit = { onEdit(config) },
                        onDelete = { pendingDelete = config.id },
                    )
                }
            }
        }
    }

    val toDelete = state.connections.find { it.id == pendingDelete }
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.delete_connection_confirm, toDelete.displayName())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(toDelete)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectionRow(config: ConnectionConfig, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(color = KonsoleColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.displayName(),
                    fontFamily = Monospace,
                    fontSize = 15.sp,
                    color = KonsoleColors.TextPrimary,
                )
                Text(
                    stringResource(R.string.connection_details, config.username, config.host, config.port),
                    fontFamily = Monospace,
                    fontSize = 12.sp,
                    color = KonsoleColors.TextSecondary,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.content_desc_edit),
                    tint = KonsoleColors.Accent,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.content_desc_delete),
                    tint = KonsoleColors.Error,
                )
            }
        }
    }
}

/** Keeps the last row clear of the floating add button. */
private val FAB_CLEARANCE = 88.dp

internal const val LIST_TAG = "connection_list"

@Preview
@Composable
private fun ConnectionListPreview() = KonsoleTheme {
    ConnectionListScreen(
        state = ConnectionsViewModel.UiState(
            listOf(
                ConnectionConfig(id = "1", name = "Gateway", host = "gw.example.org", username = "admin"),
                ConnectionConfig(id = "2", host = "10.0.0.5", port = 2222, username = "pi"),
            ),
        ),
        onBack = {},
        onAdd = {},
        onEdit = {},
        onDelete = {},
    )
}
