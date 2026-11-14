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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.sync.SyncManager
import hu.billman.konsolessh.ui.SyncViewModel.Problem
import hu.billman.konsolessh.ui.SyncViewModel.Step
import hu.billman.konsolessh.ui.theme.KonsoleColors
import java.text.DateFormat
import java.util.Date

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
    Body(stringResource(R.string.sync_intro))
    PrimaryButton(stringResource(R.string.sync_connect), onClick = actions.onConnect)
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

@Composable
private fun On(status: SyncManager.Status, actions: SyncActions) {
    val line = when (status) {
        SyncManager.Status.Syncing -> stringResource(R.string.sync_syncing)
        is SyncManager.Status.Ready -> status.lastSyncAt?.let {
            stringResource(R.string.sync_last, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
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
