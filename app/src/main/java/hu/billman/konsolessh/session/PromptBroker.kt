package hu.billman.konsolessh.session

import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SessionPrompter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * A JSch blokkoló kérdéseit (jelszó, host key) állapottá alakítja: a kérés a
 * [pending]-ben jelenik meg, a felület megjeleníti és [answer]-rel válaszol.
 * Így a kérdés túléli a forgatást, és a munkamenet nem tart referenciát a
 * felületre. Válasz nélkül [timeoutMs] után elutasít.
 */
class PromptBroker(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : SessionPrompter {

    private val _pending = MutableStateFlow<PendingPrompt?>(null)
    val pending: StateFlow<PendingPrompt?> = _pending.asStateFlow()

    private class Waiting(val prompt: PendingPrompt, val deferred: CompletableDeferred<PromptAnswer?>)

    @Volatile private var waiting: Waiting? = null

    /** A [cancel] után nincs, aki válaszoljon: minden további kérdés azonnal elutasítva. */
    @Volatile private var cancelled = false

    override fun password(displayHost: String): String? =
        (ask { id -> PendingPrompt.Password(id, displayHost) } as? PromptAnswer.Password)?.password

    override fun confirmHostKey(prompt: HostKeyPrompt): Boolean =
        (ask { id -> PendingPrompt.HostKey(id, prompt) } as? PromptAnswer.HostKey)?.accept == true

    /** Egy elavult kérdésre vagy más fajtájú válasz figyelmen kívül marad. */
    fun answer(promptId: Long, answer: PromptAnswer) {
        val current = waiting ?: return
        val matches = when (current.prompt) {
            is PendingPrompt.Password -> answer is PromptAnswer.Password
            is PendingPrompt.HostKey -> answer is PromptAnswer.HostKey
        }
        if (current.prompt.id == promptId && matches) current.deferred.complete(answer)
    }

    /**
     * Lezárja a függő kérdést, és minden későbbit is elutasít (pl. a fül
     * bezárásakor). Enélkül egy kapcsolódás közben bezárt fül későbbi kérdése
     * az időkorlátig tartaná nyitva a félkész kapcsolatot.
     */
    fun cancel() {
        cancelled = true
        waiting?.deferred?.complete(null)
    }

    // Called on the JSch IO thread, which must block until the user answers.
    private fun ask(build: (Long) -> PendingPrompt): PromptAnswer? {
        if (cancelled) return null
        val prompt = build(NEXT_ID.incrementAndGet())
        val deferred = CompletableDeferred<PromptAnswer?>()
        waiting = Waiting(prompt, deferred)
        _pending.value = prompt
        return try {
            runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        } finally {
            waiting = null
            _pending.value = null
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        val NEXT_ID = AtomicLong()
    }
}
