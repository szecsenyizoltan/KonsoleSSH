package hu.billman.konsolessh.ui

import android.content.Context
import hu.billman.konsolessh.R
import hu.billman.konsolessh.ssh.LocalNetworkAccess
import hu.billman.konsolessh.ssh.SshError

/** Az egyetlen hely, ahol egy [SshError]-ból felhasználói szöveg lesz. */
object SshErrorMessages {

    fun text(context: Context, error: SshError): String {
        // Android 17+ silently drops LAN traffic without the permission; the
        // symptom is a plain timeout, so name the real cause.
        val looksBlocked = error == SshError.Timeout || error == SshError.NotPermitted
        val lanBlocked = looksBlocked && LocalNetworkAccess.isDenied(context)
        val res = resourceFor(error)
        return when {
            lanBlocked -> context.getString(R.string.err_local_network_blocked)
            res != null -> context.getString(res)
            else -> (error as? SshError.Other)?.detail.orEmpty()
        }
    }

    private fun resourceFor(error: SshError): Int? = when (error) {
        SshError.ConnectionRefused -> R.string.err_connection_refused
        SshError.Timeout, SshError.NotPermitted -> R.string.err_timeout
        SshError.NoRoute -> R.string.err_no_route
        SshError.NetworkUnreachable -> R.string.err_network_unreachable
        SshError.UnknownHost -> R.string.err_unknown_host
        SshError.BadCredentials -> R.string.err_bad_credentials
        SshError.AlgorithmMismatch -> R.string.err_algo_mismatch
        SshError.AuthCancelled -> R.string.err_auth_cancel
        SshError.UserAuthFailed -> R.string.err_userauth_failed
        SshError.AuthFailed -> R.string.err_auth_failed
        SshError.ConnectionClosed -> R.string.err_conn_closed
        SshError.BrokenPipe -> R.string.err_broken_pipe
        SshError.PortForwarding -> R.string.err_port_forwarding
        SshError.Channel -> R.string.err_channel
        SshError.HostKeyRejected -> R.string.err_hostkey_rejected
        SshError.HostKeyChanged -> R.string.err_hostkey_changed
        SshError.JumpNotFound -> R.string.err_jump_not_found
        SshError.JumpChainInvalid -> R.string.err_jump_chain_invalid
        SshError.ConnectionMissing -> R.string.err_connection_missing
        SshError.StoreUnreadable -> R.string.err_store_unreadable
        is SshError.Other -> null
    }
}
