package hu.billman.konsolessh.ssh

import com.jcraft.jsch.Session
import hu.billman.konsolessh.model.ConnectionConfig

/** A jump-lánc nem építhető fel a mentett kapcsolatokból. */
class JumpChainException(val reason: Reason) : IllegalStateException("jump chain: $reason") {
    enum class Reason { NOT_FOUND, CYCLE, TOO_LONG }
}

/**
 * Többlépcsős jump host (ssh -J a,b,c). A jump host maga is hivatkozhat egy
 * jump hostra, így elérhető például egy belső hálózaton futó hipervizoron
 * (virsh/QEMU) lévő VM kívülről is.
 */
object JumpChain {

    const val MAX_HOPS = 5
    private const val LOOPBACK = "127.0.0.1"

    /** A célig vezető ugrópontok, a legkülső elsőként; a célt nem tartalmazza. */
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

    private fun nextHop(cfg: ConnectionConfig, saved: List<ConnectionConfig>): ConnectionConfig? = when {
        cfg.jumpConnectionId.isNotBlank() ->
            saved.find { it.id == cfg.jumpConnectionId }
                ?: throw JumpChainException(JumpChainException.Reason.NOT_FOUND)
        // Régi, kézzel megadott jump: nincs további hivatkozása, a lánc itt véget ér.
        cfg.jumpHost.isNotBlank() -> ConnectionConfig(
            host = cfg.jumpHost,
            port = cfg.jumpPort,
            username = cfg.jumpUsername,
            password = cfg.jumpPassword,
        )
        else -> null
    }

    /**
     * Sorban kapcsolódik a [hops] elemeihez (az utolsó a cél). Minden köztes
     * session egy helyi portot továbbít a következő hophoz, amelyhez az [open]
     * már a 127.0.0.1-en át kapcsolódik; a host key a valódi néven ([alias])
     * ellenőrződik. Hiba esetén a már nyitott sessionöket lezárja.
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
