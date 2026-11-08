package hu.billman.konsolessh.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import hu.billman.konsolessh.R
import hu.billman.konsolessh.ssh.HostKeyPrompt

/**
 * Host key jóváhagyás. Megváltozott kulcsnál a hangsúlyos gomb az elutasítás,
 * mert az lehet MITM-támadás jele.
 */
object HostKeyDialog {

    fun show(context: Context, prompt: HostKeyPrompt, onResult: (Boolean) -> Unit) {
        var answered = false
        val answer: (Boolean) -> Unit = { accepted ->
            if (!answered) {
                answered = true
                onResult(accepted)
            }
        }
        val builder = AlertDialog.Builder(context, R.style.KonsoleDialog)
            .setCancelable(false)
            .setOnDismissListener { answer(false) }
        if (prompt.changed) {
            builder.setTitle(R.string.hostkey_changed_title)
                .setMessage(
                    context.getString(R.string.hostkey_changed_message, prompt.host, prompt.keyType, prompt.fingerprint)
                )
                .setPositiveButton(R.string.action_cancel) { _, _ -> answer(false) }
                .setNegativeButton(R.string.hostkey_replace) { _, _ -> answer(true) }
        } else {
            builder.setTitle(R.string.hostkey_unknown_title)
                .setMessage(
                    context.getString(R.string.hostkey_unknown_message, prompt.host, prompt.keyType, prompt.fingerprint)
                )
                .setPositiveButton(R.string.hostkey_accept) { _, _ -> answer(true) }
                .setNegativeButton(R.string.action_cancel) { _, _ -> answer(false) }
        }
        builder.show()
    }
}
