package hu.billman.konsolessh.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.PendingPrompt

/**
 * A munkamenet kérdései. Csak a gombok válaszolnak: ha a dialógust a rendszer
 * zárja be (forgatás), a kérdés függőben marad, és az új Activity újra
 * megjeleníti.
 */
object PromptDialogs {

    fun show(context: Context, prompt: PendingPrompt, onAnswer: (Any?) -> Unit): Dialog = when (prompt) {
        is PendingPrompt.Password -> password(context, prompt.displayHost, onAnswer)
        is PendingPrompt.HostKey -> hostKey(context, prompt, onAnswer)
    }

    private fun password(context: Context, displayHost: String, onAnswer: (String?) -> Unit): Dialog {
        val padding = context.resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = context.getString(R.string.password)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        return AlertDialog.Builder(context, R.style.KonsoleDialog)
            .setTitle(displayHost)
            .setMessage(R.string.password_prompt_message)
            .setView(editText)
            .setPositiveButton(R.string.action_ok) { _, _ -> onAnswer(editText.text.toString()) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> onAnswer(null) }
            .setCancelable(false)
            .show()
    }

    // A changed key may mean a MITM attack, so rejecting is the emphasised button.
    private fun hostKey(context: Context, prompt: PendingPrompt.HostKey, onAnswer: (Boolean) -> Unit): Dialog {
        val key = prompt.prompt
        val builder = AlertDialog.Builder(context, R.style.KonsoleDialog).setCancelable(false)
        if (key.changed) {
            builder.setTitle(R.string.hostkey_changed_title)
                .setMessage(context.getString(R.string.hostkey_changed_message, key.host, key.keyType, key.fingerprint))
                .setPositiveButton(R.string.action_cancel) { _, _ -> onAnswer(false) }
                .setNegativeButton(R.string.hostkey_replace) { _, _ -> onAnswer(true) }
        } else {
            builder.setTitle(R.string.hostkey_unknown_title)
                .setMessage(context.getString(R.string.hostkey_unknown_message, key.host, key.keyType, key.fingerprint))
                .setPositiveButton(R.string.hostkey_accept) { _, _ -> onAnswer(true) }
                .setNegativeButton(R.string.action_cancel) { _, _ -> onAnswer(false) }
        }
        return builder.show()
    }
}
