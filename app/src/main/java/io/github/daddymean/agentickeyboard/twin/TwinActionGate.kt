package io.github.daddymean.agentickeyboard.twin

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox

/**
 * KEYBOARD-043: runs [onAllowed] only if [TwinActionPolicy] allows [action],
 * asking Keith first when the policy says so.
 */
object TwinActionGate {
    fun run(activity: Activity, action: TwinAction, detail: String? = null, onAllowed: () -> Unit) {
        val policy = TwinActionPolicy.of(activity)
        when (policy.decide(action)) {
            TwinActionPolicy.Decision.ALLOW -> onAllowed()
            TwinActionPolicy.Decision.ASK_ONCE -> {
                val remember = CheckBox(activity).apply { text = "Don't ask again for this (undo in twin settings)"; isChecked = true }
                AlertDialog.Builder(activity).setTitle("${action.label}?").setMessage(detail).setView(remember)
                    .setPositiveButton("Allow") { _, _ -> policy.approved(action, remember.isChecked); onAllowed() }
                    .setNegativeButton("Cancel", null).show()
            }
            TwinActionPolicy.Decision.ASK_ALWAYS -> {
                val kind = if (action.risk == TwinActionPolicy.Risk.DANGEROUS) "This can't be undone easily." else "This handles private twin data."
                AlertDialog.Builder(activity).setTitle("${action.label}?")
                    .setMessage(listOfNotNull(detail, "$kind You'll be asked every time.").joinToString("\n\n"))
                    .setPositiveButton("Allow this time") { _, _ -> onAllowed() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }
}
