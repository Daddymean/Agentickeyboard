package io.github.daddymean.agentickeyboard.util

/** Helpers for presenting per-app persona mappings. Pure JVM so it is unit-testable. */
object AppPersonas {
    /**
     * Best display name for an app: the stored [appLabel] when the IME could
     * resolve one, otherwise a tidied last segment of the [packageName] (e.g.
     * "com.whatsapp" → "Whatsapp") so the Style Hub never shows a raw package id
     * when it can avoid it.
     */
    fun friendlyName(appLabel: String?, packageName: String): String {
        val label = appLabel?.trim().orEmpty()
        if (label.isNotEmpty()) return label
        val segment = packageName.substringAfterLast('.').ifBlank { packageName }
        return segment.replaceFirstChar { it.uppercaseChar() }
    }
}
