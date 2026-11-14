package hu.billman.konsolessh.ui

import android.app.PendingIntent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.sync.DriveAuthorizer
import hu.billman.konsolessh.sync.DriveFullException
import hu.billman.konsolessh.sync.SyncCipher
import hu.billman.konsolessh.sync.SyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The Google Drive sync screen, and the first-start offer to restore from
 * Drive. Setting up runs in steps: connect (which needs the user's
 * consent in Google's screen the first time), look for a backup, then
 * enter its passphrase or choose a new one.
 */
class SyncViewModel(
    private val sync: SyncManager,
    private val settings: SettingsRepository,
    private val connections: ConnectionRepository,
) : ViewModel() {

    enum class Step { INTRO, CHECKING, RESTORE, CREATE, ENABLING, ON }

    enum class Problem {
        CONSENT,
        NOT_CONFIGURED,
        NETWORK,
        DRIVE_FULL,
        WRONG_PASSPHRASE,
        PASSPHRASE_CHANGED,
        NEWER_FORMAT,
        STORE,
        MISMATCH,
        TOO_SHORT,
    }

    data class UiState(
        val step: Step = Step.INTRO,
        val status: SyncManager.Status = SyncManager.Status.Off,
        val problem: Problem? = null,
    )

    private val step = MutableStateFlow(Step.INTRO)
    private val problem = MutableStateFlow<Problem?>(null)

    val state: StateFlow<UiState> = combine(step, sync.status, problem) { step, status, problem ->
        val shown = if (status != SyncManager.Status.Off) Step.ON else step
        val failure = (status as? SyncManager.Status.Failed)?.let { classify(it.cause, joining = false) }
        UiState(shown, status, problem ?: failure)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _consent = MutableStateFlow<PendingIntent?>(null)

    /** Google's consent screen to show; the activity reports back with [onConsentResult]. */
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    private var afterConsent: (() -> Unit)? = null

    private val hasConnections = MutableStateFlow(true)

    /** Offer a restore on a first start: no connections, sync off, not declined before. */
    val offerRestore: StateFlow<Boolean> =
        combine(hasConnections, sync.status, settings.syncOfferDismissed) { has, status, dismissed ->
            !has && status == SyncManager.Status.Off && !dismissed
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            hasConnections.value = runCatching { connections.load().isNotEmpty() }.getOrDefault(true)
        }
    }

    fun connect() = run(joining = false) {
        step.value = Step.CHECKING
        step.value = if (sync.backupExists()) Step.RESTORE else Step.CREATE
    }

    fun enable(passphrase: String, repeat: String?) {
        problem.value = when {
            passphrase.length < MIN_PASSPHRASE -> Problem.TOO_SHORT
            repeat != null && repeat != passphrase -> Problem.MISMATCH
            else -> null
        }
        if (problem.value != null) return
        val back = step.value
        run(joining = back == Step.RESTORE, onFailure = { step.value = back }) {
            step.value = Step.ENABLING
            sync.enable(passphrase.toCharArray())
        }
    }

    fun syncNow() = run(joining = false) { sync.syncNow() }

    /** Google access is missing: show the consent screen a background sync ran into, or start over. */
    fun grant() {
        val failed = (sync.status.value as? SyncManager.Status.Failed)?.cause as? DriveAuthorizer.ConsentNeededException
        if (failed == null) {
            connect()
            return
        }
        afterConsent = { syncNow() }
        _consent.value = failed.intent
    }

    fun disable() = run(joining = false) {
        sync.disable()
        step.value = Step.INTRO
    }

    fun dismissOffer() {
        viewModelScope.launch { settings.dismissSyncOffer() }
    }

    fun onConsentShown() {
        _consent.value = null
    }

    /**
     * Google's consent screen closed. Granted, the action that needed it runs
     * again. Closed by the user, the consent problem stays; failed for any
     * other reason, such as an OAuth client missing for this signing key,
     * sync is not set up for this build.
     */
    fun onConsentResult(granted: Boolean, failure: ApiException? = null) {
        val retry = afterConsent
        afterConsent = null
        when {
            granted -> retry?.invoke()
            failure != null && failure.statusCode != CommonStatusCodes.CANCELED -> problem.value = Problem.NOT_CONFIGURED
        }
    }

    private fun run(joining: Boolean, onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        problem.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: DriveAuthorizer.ConsentNeededException) {
                failed(e, joining, onFailure)
                // Google's screen opens; once access is granted the same action runs again.
                afterConsent = { run(joining, onFailure, block) }
                _consent.value = e.intent
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                failed(e, joining, onFailure)
            }
        }
    }

    private fun failed(e: Exception, joining: Boolean, onFailure: () -> Unit) {
        // The screen shows a short reason; the log keeps the cause for a problem report.
        Log.w(TAG, "sync step failed", e)
        onFailure()
        if (step.value == Step.CHECKING) step.value = Step.INTRO
        problem.value = classify(e, joining)
    }

    private fun classify(e: Throwable, joining: Boolean): Problem = when (e) {
        is DriveAuthorizer.ConsentNeededException -> Problem.CONSENT
        is ApiException -> if (e.statusCode == CommonStatusCodes.DEVELOPER_ERROR) Problem.NOT_CONFIGURED else Problem.NETWORK
        is SyncCipher.WrongPassphraseException -> if (joining) Problem.WRONG_PASSPHRASE else Problem.PASSPHRASE_CHANGED
        is SyncCipher.UnreadableFileException -> Problem.NEWER_FORMAT
        is ConnectionStoreException -> Problem.STORE
        is DriveFullException -> Problem.DRIVE_FULL
        is IOException -> Problem.NETWORK
        else -> Problem.NETWORK
    }

    private companion object {
        const val TAG = "Sync"
        const val MIN_PASSPHRASE = 8
    }
}
