package io.github.kilianm.refreeplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * The thin timeline shown when the replay controls are hidden: progress so far, or, in the cut
 * view, the selected part plus a marker at the current position. Takes no touches.
 */
class MiniTimeline @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint().apply { color = 0x66FFFFFF }
    private val fillPaint = Paint().apply { color = 0xFFE53935.toInt() }
    private val markerPaint = Paint().apply { color = 0xFFFFFFFF.toInt() }

    /** Current position, 0..1. */
    private var position = 0f

    /** Selected part (cut view), 0..1, or null for a plain progress line. */
    private var selection: Pair<Float, Float>? = null

    fun update(position: Float, selection: Pair<Float, Float>?) {
        val p = position.coerceIn(0f, 1f)
        if (p == this.position && selection == this.selection) return
        this.position = p
        this.selection = selection
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (12 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val track = 3 * density
        val top = h - track
        canvas.drawRect(0f, top, w, h, trackPaint)
        val sel = selection
        if (sel == null) {
            canvas.drawRect(0f, top, w * position, h, fillPaint)
        } else {
            // Selected part as a thicker red band, current position as a white marker.
            val band = 6 * density
            canvas.drawRect(w * sel.first, h - band, w * sel.second, h, fillPaint)
            val x = w * position
            val half = 1.5f * density
            canvas.drawRect(x - half, 0f, x + half, h, markerPaint)
        }
    }
}
