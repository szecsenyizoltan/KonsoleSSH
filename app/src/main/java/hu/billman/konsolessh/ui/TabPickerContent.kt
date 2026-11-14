package hu.billman.konsolessh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace

/**
 * The tab picker: a new-connection entry, the open tabs, the saved
 * connections grouped by name prefix, and exit. Draws [PickerRows] as they
 * are; deleting asks first.
 */
@Composable
internal fun TabPickerContent(
    openTabs: List<PickerRows.OpenTab>,
    saved: List<PickerRows.Saved>,
    actions: TabPickerActions,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxHeight().background(KonsoleColors.Surface)) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .align(Alignment.CenterHorizontally)
                .size(width = 32.dp, height = 4.dp)
                .background(Color(0xFF555555), RoundedCornerShape(2.dp)),
        )
        EntryRow(stringResource(R.string.new_connection_dots), KonsoleColors.Accent, actions.onNewConnection) {
            Text(stringResource(R.string.sym_plus), color = KonsoleColors.Accent, fontSize = 24.sp)
        }
        HorizontalDivider(color = Color(0xFF333333))
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (openTabs.isNotEmpty()) {
                item { Header(stringResource(R.string.open_tabs)) }
                items(openTabs, key = { "tab-${it.index}" }) { tab ->
                    OpenTabRow(tab) { actions.onSelectTab(tab.index) }
                }
            }
            if (saved.isNotEmpty()) {
                item { Header(stringResource(R.string.manage_connections)) }
                items(saved, key = { row -> savedKey(row) }) { row ->
                    when (row) {
                        is PickerRows.Saved.Group -> GroupRow(row) { actions.onToggleGroup(row.path) }
                        is PickerRows.Saved.Connection -> ConnectionRow(
                            row = row,
                            onOpen = { actions.onOpen(row.config) },
                            onEdit = { actions.onEdit(row.config) },
                            onDelete = { pendingDelete = row.config.id },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF333333))
        EntryRow(stringResource(R.string.exit), Color(0xFF888888), actions.onExit) {
            Icon(
                painter = painterResource(R.drawable.ic_exit),
                contentDescription = stringResource(R.string.content_desc_exit),
                tint = Color(0xFF888888),
            )
        }
    }

    val toDelete = saved.filterIsInstance<PickerRows.Saved.Connection>().find { it.config.id == pendingDelete }?.config
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            text = { Text(stringResource(R.string.delete_connection_confirm, toDelete.displayName())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    actions.onDelete(toDelete)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun savedKey(row: PickerRows.Saved) = when (row) {
    is PickerRows.Saved.Group -> "group-${row.path}"
    is PickerRows.Saved.Connection -> "connection-${row.config.id}"
}

@Composable
private fun EntryRow(text: String, color: Color, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, color = color, fontSize = 15.sp, fontFamily = Monospace, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text.uppercase(),
        color = KonsoleColors.TextSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** One picker row: icon, title and subtitle, then optional trailing content. */
@Composable
private fun PickerRow(
    icon: String,
    iconColor: Color,
    title: String,
    subtitle: String?,
    depth: Int,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp + DEPTH_STEP * depth, end = 16.dp)
            .height(56.dp),
    ) {
        Text(icon, color = iconColor, fontSize = 16.sp, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = KonsoleColors.TextPrimary, fontSize = 14.sp, fontFamily = Monospace, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = KonsoleColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = Monospace,
                    maxLines = 1,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun OpenTabRow(tab: PickerRows.OpenTab, onClick: () -> Unit) {
    PickerRow(
        icon = if (tab.active) "▶" else "●",
        iconColor = statusColor(tab.status),
        title = tab.title,
        subtitle = tab.host,
        depth = 0,
        onClick = onClick,
    ) {
        if (tab.active) Text(stringResource(R.string.tab_active), color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun GroupRow(group: PickerRows.Saved.Group, onClick: () -> Unit) {
    PickerRow(
        icon = if (group.expanded) "📂" else "📁",
        iconColor = Color.Unspecified,
        title = group.label,
        subtitle = stringResource(R.string.group_n_connections, group.count),
        depth = group.depth,
        onClick = onClick,
    ) {
        Text(
            text = if (group.expanded) "▼" else "▶",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun ConnectionRow(
    row: PickerRows.Saved.Connection,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = row.config
    PickerRow(
        icon = "⚡",
        iconColor = Color.Unspecified,
        title = row.title,
        subtitle = "${config.username}@${config.host}:${config.port}",
        depth = row.depth,
        onClick = onOpen,
    ) {
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

/** The colour of a tab's status dot; shared with the tab strip. */
fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> KonsoleColors.Success
    ConnectionStatus.CONNECTING -> KonsoleColors.Warning
    ConnectionStatus.DISCONNECTED -> KonsoleColors.Error
    ConnectionStatus.NONE -> Color.Gray
}

private val DEPTH_STEP = 20.dp
