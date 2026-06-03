package com.waqas.carlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A simple analog speedometer: a 240° arc from 0 to 120 km/h with a major tick
 * and label every 20 km/h, a sweeping needle, and the numeric speed in the
 * centre. The open gap sits at the bottom (needle points lower-left at 0,
 * straight up at 60, lower-right at 120).
 *
 * Colours are deliberately dim to suit the near-black night-mode screen.
 * Call [setSpeed] with a value in km/h; the needle animates to the new value.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxSpeed = 120f
    private val majorStep = 20
    private val startAngle = 150f   // 0 km/h, lower-left
    private val sweepAngle = 240f   // clockwise to lower-right (120 km/h)

    private val density = resources.displayMetrics.density

    private val dialColor = Color.parseColor("#FF606060")
    private val tickColor = Color.parseColor("#FF707070")
    private val labelColor = Color.parseColor("#FF909090")
    private val needleColor = Color.parseColor("#FFB23A3A")

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = dialColor
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = tickColor
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = needleColor
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = dialColor
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tickColor
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    private var displayedSpeed = 0f
    private var animator: ValueAnimator? = null

    /** Set the speed to show, in km/h. Values are clamped to the dial range. */
    fun setSpeed(kmh: Float) {
        val target = kmh.coerceIn(0f, maxSpeed)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(displayedSpeed, target).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedSpeed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private fun angleFor(value: Float): Double =
        Math.toRadians((startAngle + sweepAngle * value / maxSpeed).toDouble())

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 6f * density

        arcPaint.strokeWidth = 3f * density
        tickPaint.strokeWidth = 3f * density
        needlePaint.strokeWidth = 4f * density
        labelPaint.textSize = radius * 0.16f
        speedPaint.textSize = radius * 0.34f
        unitPaint.textSize = radius * 0.13f

        // Dial arc
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, arcPaint)

        // Major ticks + labels
        var value = 0
        while (value <= maxSpeed.toInt()) {
            val angle = angleFor(value.toFloat())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + cosA * radius * 0.84f, cy + sinA * radius * 0.84f,
                cx + cosA * radius * 0.98f, cy + sinA * radius * 0.98f,
                tickPaint
            )
            val lx = cx + cosA * radius * 0.68f
            val ly = cy + sinA * radius * 0.68f
            val fm = labelPaint.fontMetrics
            canvas.drawText(value.toString(), lx, ly - (fm.ascent + fm.descent) / 2f, labelPaint)
            value += majorStep
        }

        // Needle
        val needleAngle = angleFor(displayedSpeed)
        val nCos = cos(needleAngle).toFloat()
        val nSin = sin(needleAngle).toFloat()
        canvas.drawLine(
            cx - nCos * radius * 0.12f, cy - nSin * radius * 0.12f,
            cx + nCos * radius * 0.80f, cy + nSin * radius * 0.80f,
            needlePaint
        )

        // Hub
        canvas.drawCircle(cx, cy, radius * 0.06f, hubPaint)

        // Numeric speed + unit, lower-centre inside the dial
        val fm = speedPaint.fontMetrics
        val speedY = cy + radius * 0.42f
        canvas.drawText(
            displayedSpeed.toInt().toString(),
            cx, speedY - (fm.ascent + fm.descent) / 2f, speedPaint
        )
        canvas.drawText("km/h", cx, speedY + speedPaint.textSize * 0.6f, unitPaint)
    }
}
