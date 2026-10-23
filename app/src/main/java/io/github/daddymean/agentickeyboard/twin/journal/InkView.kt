package io.github.daddymean.agentickeyboard.twin.journal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View

/** KEYBOARD-037: a writing surface. It keeps every stroke in [ink] and nothing else. */
class InkView(context: Context, heightPx: Int) : View(context) {
    var ink: InkStrokes = InkStrokes(1, heightPx)
        private set
    private val h0 = heightPx

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (ink.isEmpty) ink = InkStrokes(w, h)
    }

    fun clear() { ink.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        InkStrokes.draw(canvas, ink.drawable())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> ink.down(e.x, e.y, t)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) ink.move(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                ink.move(e.x, e.y, t)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ink.up(e.x, e.y, t)
            else -> return false
        }
        invalidate()
        return true
    }
}
