package io.github.kilianm.refreeplay

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/** SurfaceView that keeps the camera's aspect ratio and fits inside its parent. */
class AspectSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    private var ratio = 16f / 9f

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        ratio = width.toFloat() / height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (w == 0 || h == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var mw = w
        var mh = (w / ratio).toInt()
        if (mh > h) {
            mh = h
            mw = (h * ratio).toInt()
        }
        setMeasuredDimension(mw, mh)
    }
}
