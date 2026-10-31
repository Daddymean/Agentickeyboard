package io.github.daddymean.agentickeyboard.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.github.daddymean.agentickeyboard.TwinMemoryActivity
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlin.math.abs

/**
 * KEYBOARD-044: the optional floating quick-ask button, a TYPE_APPLICATION_OVERLAY
 * window owned by the keyboard service. It contains one fixed label, reads nothing
 * from the screen or the field, and opens the twin's existing cited-answer screen.
 * Visibility is decided by [io.github.daddymean.agentickeyboard.twin.TwinBubblePolicy].
 *
 * The floating-button idea comes from jackwener/DSH-Android (Apache-2.0); this is a
 * clean re-implementation.
 */
class TwinQuickAskBubble(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0
        y = (160 * context.resources.displayMetrics.density).toInt()
        title = "Twin quick ask"
    }

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun setVisible(visible: Boolean) {
        if (visible && canDraw()) show() else hide()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (view != null) return
        val d = context.resources.displayMetrics.density
        val bubble = TextView(context).apply {
            text = "Ask twin"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(0xE0335C81.toInt()) }
            contentDescription = "Ask the twin"
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 * d || abs(dy) > 8 * d) moved = true
                    if (moved) { params.x = startX - dx.toInt(); params.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, params) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) { v.performClick(); openAsk() }
            }
            true
        }
        runCatching { wm.addView(bubble, params) }
            .onSuccess { view = bubble }
            .onFailure { SafeLog.w(TAG, "Bubble not shown", it) }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    private fun openAsk() {
        runCatching {
            context.startActivity(
                Intent(context, TwinMemoryActivity::class.java)
                    .putExtra(TwinMemoryActivity.EXTRA_OPEN_ASK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.onFailure { SafeLog.w(TAG, "Couldn't open the twin", it) }
    }

    private companion object { const val TAG = "TwinBubble" }
}
