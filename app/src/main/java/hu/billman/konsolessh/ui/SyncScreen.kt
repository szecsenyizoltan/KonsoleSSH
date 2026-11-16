package hu.billman.konsolessh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.sync.SyncManager
import hu.billman.konsolessh.ui.SyncViewModel.Problem
import hu.billman.konsolessh.ui.SyncViewModel.Step
import hu.billman.konsolessh.ui.theme.KonsoleColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(state: SyncViewModel.UiState, actions: SyncActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_sync)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KonsoleColors.Toolbar),
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            when (state.step) {
                Step.INTRO -> Intro(actions)
                Step.CHECKING -> Working(stringResource(R.string.sync_checking))
                Step.RESTORE -> Passphrase(restore = true, actions)
                Step.CREATE -> Passphrase(restore = false, actions)
                Step.ENABLING -> Working(stringResource(R.string.sync_working))
                Step.ON -> On(state.status, actions)
            }
            state.problem?.let { ProblemText(it, actions) }
        }
    }
}

@Composable
private fun Intro(actions: SyncActions) {
    SyncHelp()
    PrimaryButton(stringResource(R.string.sync_connect), onClick = actions.onConnect)
}

/** What sync is for and what it does, so the user can decide before connecting Drive. */
private val helpSections = listOf(
    R.string.sync_help_purpose_title to R.string.sync_help_purpose,
    R.string.sync_help_what_title to R.string.sync_help_what,
    R.string.sync_help_where_title to R.string.sync_help_where,
    R.string.sync_help_privacy_title to R.string.sync_help_privacy,
    R.string.sync_help_when_title to R.string.sync_help_when,
    R.string.sync_help_conflict_title to R.string.sync_help_conflict,
    R.string.sync_help_off_title to R.string.sync_help_off,
)

@Composable
private fun SyncHelp() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        helpSections.forEach { (title, text) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(title),
                    color = KonsoleColors.TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(text), color = KonsoleColors.TextSecondary)
            }
        }
    }
}

/** With sync on, the same explanation stays at hand, folded away. */
@Composable
private fun FoldedHelp() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(stringResource(R.string.sync_help_title), color = KonsoleColors.Accent)
    }
    if (open) SyncHelp()
}

@Composable
private fun Passphrase(restore: Boolean, actions: SyncActions) {
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    Body(stringResource(if (restore) R.string.sync_found else R.string.sync_new))
    SecretField(first, { first = it }, stringResource(R.string.sync_passphrase))
    if (!restore) SecretField(second, { second = it }, stringResource(R.string.sync_passphrase_repeat))
    PrimaryButton(stringResource(if (restore) R.string.sync_restore else R.string.sync_start)) {
        actions.onEnable(first, if (restore) null else second)
    }
}

// Built on every call: the formatter takes the locale when it is created,
// and the user can switch the app language while it runs.
private fun formatSyncTime(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

@Composable
private fun On(status: SyncManager.Status, actions: SyncActions) {
    val line = when (status) {
        SyncManager.Status.Syncing -> stringResource(R.string.sync_syncing)
        is SyncManager.Status.Ready -> status.lastSyncAt?.let {
            stringResource(R.string.sync_last, formatSyncTime(it))
        } ?: stringResource(R.string.sync_never)
        else -> stringResource(R.string.sync_never)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == SyncManager.Status.Syncing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
        }
        Body(line)
    }
    PrimaryButton(
        stringResource(R.string.sync_now),
        enabled = status != SyncManager.Status.Syncing,
        onClick = actions.onSyncNow,
    )
    OutlinedButton(onClick = actions.onDisable, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sync_turn_off))
    }
    Text(stringResource(R.string.sync_turn_off_note), color = KonsoleColors.TextSecondary)
    FoldedHelp()
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, color = KonsoleColors.TextPrimary, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ProblemText(problem: Problem, actions: SyncActions) {
    val text = stringResource(
        when (problem) {
            Problem.CONSENT -> R.string.sync_err_consent
            Problem.NOT_CONFIGURED -> R.string.sync_err_not_configured
            Problem.NETWORK -> R.string.sync_err_network
            Problem.DRIVE_FULL -> R.string.sync_err_drive_full
            Problem.WRONG_PASSPHRASE -> R.string.sync_err_wrong_passphrase
            Problem.PASSPHRASE_CHANGED -> R.string.sync_err_passphrase_changed
            Problem.NEWER_FORMAT -> R.string.sync_err_newer
            Problem.STORE -> R.string.sync_err_store
            Problem.MISMATCH -> R.string.sync_err_mismatch
            Problem.TOO_SHORT -> R.string.sync_err_short
        },
    )
    Text(text, color = KonsoleColors.Error)
    if (problem == Problem.CONSENT) PrimaryButton(stringResource(R.string.sync_grant), onClick = actions.onGrant)
}

@Composable
private fun Body(text: String) = Text(text, color = KonsoleColors.TextPrimary)

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = KonsoleColors.Accent),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text) }
}
