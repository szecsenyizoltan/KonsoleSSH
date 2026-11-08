package hu.billman.konsolessh.ssh

/**
 * Típusos SSH-hiba. A felület ebből képez szöveget; így egyetlen helyen dől
 * el, mit jelent egy JSch-kivétel, és a terminál, a kapcsolatteszt és a
 * kulcsfeltöltés ugyanazt az üzenetet adja.
 */
sealed interface SshError {
    data object ConnectionRefused : SshError
    data object Timeout : SshError

    /** EPERM: a rendszer tiltja a forgalmat (pl. helyi hálózati engedély). */
    data object NotPermitted : SshError
    data object NoRoute : SshError
    data object NetworkUnreachable : SshError
    data object UnknownHost : SshError
    data object BadCredentials : SshError
    data object AlgorithmMismatch : SshError
    data object AuthCancelled : SshError
    data object UserAuthFailed : SshError
    data object AuthFailed : SshError
    data object ConnectionClosed : SshError
    data object BrokenPipe : SshError
    data object PortForwarding : SshError
    data object Channel : SshError
    data object HostKeyRejected : SshError
    data object HostKeyChanged : SshError
    data object JumpNotFound : SshError
    data object JumpChainInvalid : SshError

    /** A tab mögötti mentett kapcsolat már nem létezik. */
    data object ConnectionMissing : SshError

    /** A mentett kapcsolatok tára nem olvasható. */
    data object StoreUnreadable : SshError
    data class Other(val detail: String) : SshError
}

object SshErrorClassifier {

    private const val MAX_DETAIL = 200

    // Order matters: the more specific text must come first.
    private val RULES: List<Pair<List<String>, SshError>> = listOf(
        listOf("hostkey has been changed") to SshError.HostKeyChanged,
        listOf("reject hostkey") to SshError.HostKeyRejected,
        listOf("connection refused") to SshError.ConnectionRefused,
        listOf("timed out", "timeout") to SshError.Timeout,
        listOf("operation not permitted") to SshError.NotPermitted,
        listOf("no route to host") to SshError.NoRoute,
        listOf("unreachable") to SshError.NetworkUnreachable,
        listOf("unknown host", "unknownhost", "nodename nor servname", "unable to resolve") to SshError.UnknownHost,
        listOf("bad credentials") to SshError.BadCredentials,
        listOf("algorithm negotiation") to SshError.AlgorithmMismatch,
        listOf("auth cancel") to SshError.AuthCancelled,
        listOf("userauth fail") to SshError.UserAuthFailed,
        listOf("auth fail", "authentication") to SshError.AuthFailed,
        listOf("connection is closed", "closed by foreign host", "connection reset") to SshError.ConnectionClosed,
        listOf("broken pipe") to SshError.BrokenPipe,
        listOf("port forwarding") to SshError.PortForwarding,
        listOf("channel") to SshError.Channel,
    )

    fun classify(error: Throwable): SshError {
        if (error is JumpChainException) {
            return if (error.reason == JumpChainException.Reason.NOT_FOUND) {
                SshError.JumpNotFound
            } else {
                SshError.JumpChainInvalid
            }
        }
        val messages = generateSequence(error) { it.cause }.mapNotNull { it.message }.toList()
        val text = messages.joinToString(" ").lowercase()
        return RULES.firstOrNull { (markers, _) -> markers.any { it in text } }?.second
            ?: SshError.Other(detail(messages.firstOrNull() ?: error.javaClass.simpleName))
    }

    /**
     * A nyers üzenetről leveszi a JSch / Java-osztály előtagokat; ezek
     * egymásra rakódhatnak ("Session.connect: java.net.SocketException: …").
     */
    fun detail(message: String): String {
        var text = message
        while (true) {
            val stripped = text.replace(PREFIX, "")
            if (stripped == text) break
            text = stripped
        }
        return text.take(MAX_DETAIL)
    }

    private val PREFIX = Regex("^[a-zA-Z]+(\\.[a-zA-Z]+)+:\\s*")
}
