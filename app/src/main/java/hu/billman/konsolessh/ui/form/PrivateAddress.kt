package hu.billman.konsolessh.ui.form

/**
 * Whether a host, possibly still being typed, is an RFC 1918 private IPv4
 * address (10/8, 172.16/12, 192.168/16). Such hosts are usually reached
 * through a jump host, so the form shows the jump section for them.
 *
 * It answers while the user types: an octet counts once it is followed by a
 * dot or has three digits, so "192.168" and "172.20." already match, while
 * "172.2" does not yet (it may become 172.200).
 */
internal object PrivateAddress {

    private const val OCTET_DIGITS = 3

    fun isPrivate(host: String): Boolean {
        val parts = host.trim().split('.')
        if (parts.size < 2 || parts.any { part -> part.any { !it.isDigit() } }) return false
        val complete = parts.mapIndexedNotNull { i, part ->
            val finished = i < parts.lastIndex || part.length == OCTET_DIGITS
            part.toIntOrNull()?.takeIf { finished && it <= MAX_OCTET }
        }
        val first = complete.getOrNull(0)
        val second = complete.getOrNull(1)
        return first == PRIVATE_A || (first == PRIVATE_B && second in PRIVATE_B_SECOND) ||
            (first == PRIVATE_C && second == PRIVATE_C_SECOND)
    }

    private const val MAX_OCTET = 255
    private const val PRIVATE_A = 10
    private const val PRIVATE_B = 172
    private val PRIVATE_B_SECOND = 16..31
    private const val PRIVATE_C = 192
    private const val PRIVATE_C_SECOND = 168
}
