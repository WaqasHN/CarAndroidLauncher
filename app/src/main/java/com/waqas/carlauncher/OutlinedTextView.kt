package com.waqas.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView that draws a real outline behind its text. The outline pass uses
 * Paint.Style.STROKE with double the visible thickness (because half the stroke
 * is hidden inside the glyph by the subsequent fill pass).
 *
 * The setTextColor() calls during onDraw normally trigger invalidate(), which
 * would re-enter onDraw and loop. We block invalidations for the duration of
 * the dual-color setup to prevent that.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var outlineWidth = 0f
    private var outlineColor = Color.BLACK
    private var allowInvalidate = true

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.OutlinedTextView)
            outlineWidth = ta.getDimension(R.styleable.OutlinedTextView_outlineWidth, 0f)
            outlineColor = ta.getColor(R.styleable.OutlinedTextView_outlineColor, Color.BLACK)
            ta.recycle()
        }
    }

    fun setOutline(widthPx: Float, color: Int) {
        outlineWidth = widthPx
        outlineColor = color
        invalidate()
    }

    override fun invalidate() {
        if (allowInvalidate) super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            val originalColors = textColors
            val tp = paint

            allowInvalidate = false
            try {
                tp.style = Paint.Style.STROKE
                tp.strokeWidth = outlineWidth * 2f
                tp.strokeJoin = Paint.Join.ROUND
                tp.strokeMiter = 10f
                setTextColor(outlineColor)
                super.onDraw(canvas)
                tp.style = Paint.Style.FILL
                setTextColor(originalColors)
            } finally {
                allowInvalidate = true
            }
        }
        super.onDraw(canvas)
    }
}
