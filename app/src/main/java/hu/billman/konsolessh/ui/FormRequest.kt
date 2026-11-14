package hu.billman.konsolessh.ui

import androidx.compose.runtime.saveable.Saver
import java.util.UUID

/**
 * An opening of the connection form: [editId] is the edited connection, or
 * null for a new one; with [openTab], a tab is opened after saving. [id]
 * keeps the form's ViewModel apart from earlier openings.
 */
data class FormRequest(
    val editId: String?,
    val openTab: Boolean,
    val id: String = UUID.randomUUID().toString(),
) {
    companion object {
        /** For rememberSaveable, so the open form survives a configuration change. */
        val Saver: Saver<FormRequest?, List<Any>> = Saver(
            save = { request -> request?.let { listOf(it.id, it.editId.orEmpty(), it.openTab) } },
            restore = {
                FormRequest(
                    editId = (it[1] as String).ifEmpty { null },
                    openTab = it[2] as Boolean,
                    id = it[0] as String,
                )
            },
        )
    }
}
