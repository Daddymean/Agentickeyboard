package io.github.daddymean.agentickeyboard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.daddymean.agentickeyboard.util.ConversationCapturePreferences
import io.github.daddymean.agentickeyboard.util.VisibleContext
import io.github.daddymean.agentickeyboard.util.VisibleContextPolicy
import io.github.daddymean.agentickeyboard.util.VisibleTextNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads no event text; a tree is read only from an explicit IME action. */
class ConversationCaptureService : AccessibilityService() {
    companion object {
        private var connected: ConversationCaptureService? = null
        private val _invalidations = MutableStateFlow(0L)
        val invalidations = _invalidations.asStateFlow()

        fun isConnected(): Boolean = connected != null

        fun capture(packageName: String): VisibleContext? =
            connected?.readVisibleText(packageName)

        private fun invalidate() { _invalidations.value += 1 }
    }

    override fun onServiceConnected() {
        connected = this
        invalidate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // No background scraping, text/content-change subscriptions or gestures.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() != packageName) invalidate()
        if (Build.VERSION.SDK_INT >= 28 && event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED) != 0) invalidate()
    }

    override fun onInterrupt() { invalidate() }

    override fun onDestroy() {
        if (connected === this) connected = null
        invalidate()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun readVisibleText(targetPackage: String): VisibleContext? {
        if (targetPackage == packageName ||
            !ConversationCapturePreferences(this).isAllowed(targetPackage)) return null
        return runCatching {
            // The keyboard can be the active window. Choose only the focused host
            // application, never an unrelated split-screen/background window.
            val host = windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: return null
            val root = host.root ?: return null
            try {
                if (root.packageName?.toString() != targetPackage) return null
                val hostBounds = Rect().also { host.getBoundsInScreen(it) }
                val keyboardBounds = windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
                val nodes = mutableListOf<VisibleTextNode>()
                var visited = 0
                var blocked = false
                fun visit(node: AccessibilityNodeInfo, excludedParent: Boolean) {
                    if (++visited > VisibleContextPolicy.MAX_NODES) { blocked = true; return }
                    val visible = node.isVisibleToUser
                    if (!visible) return
                    if (node.isPassword) { blocked = true; return }
                    val excluded = excludedParent || node.isEditable ||
                        (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!excluded && node.childCount == 0 && !bounds.isEmpty &&
                        hostBounds.contains(bounds) &&
                        (keyboardBounds == null || !Rect.intersects(bounds, keyboardBounds))) {
                        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                            nodes.add(VisibleTextNode(node.packageName?.toString().orEmpty(),
                                it.takeLast(VisibleContextPolicy.MAX_CHARS), bounds.top, bounds.left))
                        }
                    }
                    if (excluded) return
                    for (index in 0 until node.childCount) {
                        if (blocked) break
                        val child = node.getChild(index) ?: continue
                        try { visit(child, excluded) } finally { child.recycle() }
                    }
                }
                visit(root, false)
                if (blocked) null else VisibleContextPolicy.build(targetPackage, host.id, nodes)
            } finally { root.recycle() }
        }.getOrNull()
    }
}
