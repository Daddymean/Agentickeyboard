package io.github.daddymean.agentickeyboard.util

/**
 * Whether this keyboard has been enabled in system settings and chosen as the
 * active input method, so the companion app's setup steps can show what is
 * actually done instead of looking identical before and after.
 *
 * Pure: the caller reads the system state (InputMethodManager's enabled list and
 * Settings.Secure.DEFAULT_INPUT_METHOD) and passes it in.
 */
data class KeyboardSetupStatus(val enabled: Boolean, val selected: Boolean) {
    companion object {
        /**
         * @param enabledImePackages package names of the enabled input methods.
         * @param defaultImeId the flattened component of the active input method,
         *   e.g. `com.example/.ImeService`, or null when unreadable.
         */
        fun of(
            packageName: String,
            enabledImePackages: Collection<String>,
            defaultImeId: String?
        ): KeyboardSetupStatus {
            val selected = defaultImeId?.substringBefore('/') == packageName
            // The system only lets an enabled input method be the active one.
            return KeyboardSetupStatus(
                enabled = selected || packageName in enabledImePackages,
                selected = selected
            )
        }
    }
}
