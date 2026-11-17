package hu.billman.konsolessh.ssh

import com.jcraft.jsch.Session
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The jump chain cannot be built from the saved connections. */
class JumpChainException(val reason: Reason) : IllegalStateException("jump chain: $reason") {
    enum class Reason { NOT_FOUND, CYCLE, TOO_LONG }
}

/**
 * Multi-hop jump hosts (ssh -J a,b,c). A jump host can have a jump host of
 * its own, so a VM on a hypervisor (virsh/QEMU) inside a private network
 * can be reached from outside.
 */
object JumpChain {

    const val MAX_HOPS = 5
    private const val LOOPBACK = "127.0.0.1"

    /** The hops up to the target, outermost first, without the target itself. */
    fun resolve(target: ConnectionConfig, saved: List<ConnectionConfig>): List<ConnectionConfig> {
        val chain = ArrayDeque<ConnectionConfig>()
        val seen = mutableSetOf(target.id)
        var current = target
        while (true) {
            val next = nextHop(current, saved) ?: break
            if (!seen.add(next.id)) throw JumpChainException(JumpChainException.Reason.CYCLE)
            if (chain.size == MAX_HOPS) throw JumpChainException(JumpChainException.Reason.TOO_LONG)
            chain.addFirst(next)
            current = next
        }
        return chain.toList()
    }

    /**
     * The hops for [target], looked up among all saved connections: a jump
     * host may be any saved connection, not only one the caller has loaded.
     */
    suspend fun resolve(target: ConnectionConfig, repository: ConnectionRepository): List<ConnectionConfig> =
        resolve(target, repository.load())

    private fun nextHop(cfg: ConnectionConfig, saved: List<ConnectionConfig>): ConnectionConfig? = when {
        cfg.jumpConnectionId.isNotBlank() ->
            saved.find { it.id == cfg.jumpConnectionId }
                ?: throw JumpChainException(JumpChainException.Reason.NOT_FOUND)
        // An old, hand-entered jump host refers to nothing further: the chain ends here.
        cfg.jumpHost.isNotBlank() -> ConnectionConfig(
            host = cfg.jumpHost,
            port = cfg.jumpPort,
            username = cfg.jumpUsername,
            password = cfg.jumpPassword,
        )
        else -> null
    }

    /**
     * Connects to the [hops] in order, the last being the target. Each
     * intermediate session forwards a local port to the next hop, which
     * [open] then reaches through 127.0.0.1; its host key is checked under
     * the real name ([alias]). On failure, the sessions already open are
     * closed.
     */
    suspend fun connect(
        hops: List<ConnectionConfig>,
        open: (hop: ConnectionConfig, host: String, port: Int, alias: String?) -> Session,
        onJump: suspend (hop: ConnectionConfig) -> Unit = {},
        onForwarded: suspend (next: ConnectionConfig) -> Unit = {},
    ): List<Session> {
        val opened = mutableListOf<Session>()
        var completed = false
        try {
            var localPort = 0
            hops.forEachIndexed { i, hop ->
                // A cancelled connect stops between hops instead of finishing the chain.
                currentCoroutineContext().ensureActive()
                val last = i == hops.lastIndex
                if (!last) onJump(hop)
                val session = if (i == 0) {
                    open(hop, hop.host, hop.port, null)
                } else {
                    open(hop, LOOPBACK, localPort, TofuHostKeyRepository.alias(hop.host, hop.port))
                }
                opened += session
                if (!last) {
                    val next = hops[i + 1]
                    localPort = session.setPortForwardingL(0, next.host, next.port)
                    onForwarded(next)
                }
            }
            completed = true
            return opened
        } finally {
            if (!completed) opened.asReversed().forEach { runCatching { it.disconnect() } }
        }
    }
}
