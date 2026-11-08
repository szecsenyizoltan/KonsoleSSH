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

    @Volatile private var waiting: Pair<Long, CompletableDeferred<Any?>>? = null

    override fun password(displayHost: String): String? =
        ask { id -> PendingPrompt.Password(id, displayHost) } as? String

    override fun confirmHostKey(prompt: HostKeyPrompt): Boolean =
        ask { id -> PendingPrompt.HostKey(id, prompt) } == true

    /** A válasz típusa: jelszónál String? (null = mégse), host key-nél Boolean. */
    fun answer(promptId: Long, value: Any?) {
        val (id, deferred) = waiting ?: return
        if (id == promptId) deferred.complete(value)
    }

    /** Lezárja a függő kérdést (pl. a fül bezárásakor). */
    fun cancel() {
        waiting?.second?.complete(null)
    }

    // Called on the JSch IO thread, which must block until the user answers.
    private fun ask(build: (Long) -> PendingPrompt): Any? {
        val id = NEXT_ID.incrementAndGet()
        val deferred = CompletableDeferred<Any?>()
        waiting = id to deferred
        _pending.value = build(id)
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
