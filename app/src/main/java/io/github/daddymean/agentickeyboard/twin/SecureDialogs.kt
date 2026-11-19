package io.github.daddymean.agentickeyboard.twin

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager

/**
 * Dialogs get their own window, so the activity's FLAG_SECURE doesn't cover them. Twin
 * dialogs (forgotten phrases, sources, confirmations) are shown with the flag on their window.
 */
fun AlertDialog.Builder.showSecure(): AlertDialog = create().also {
    it.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    it.show()
}

/** FLAG_SECURE on the activity window and no Recents screenshot. */
fun Activity.secureTwinWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    setRecentsScreenshotEnabled(false)
}
