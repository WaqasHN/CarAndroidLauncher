package com.waqas.carlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A neon analog speedometer styled after a glossy gauge icon: a glowing 240°
 * arc that runs blue → cyan → white → red from 0 to 140 km/h, fine tick marks,
 * a tapered glowing needle, and the live speed shown as a cyan 7-segment
 * readout (GPS icon · digits · km/h) below the hub. The open gap sits at the
 * bottom.
 *
 * Everything is drawn with soft glows (shadow layers), so the view runs on a
 * software layer. Built bright to stay readable in daylight on the black
 * screensaver screen.
 *
 * The analog dial can be overridden by supplying drawables named "speedo_dial"
 * and "speedo_needle" (static dial image + rotating needle image) — see
 * [drawImageGauge]. A [Mode.DIGITAL] readout is also available.
 *
 * Call [setSpeed] with a value in km/h; the needle animates to the new value.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxSpeed = 140f
    private val majorStep = 20
    private val startAngle = 150f   // 0 km/h, lower-left
    private val sweepAngle = 240f   // clockwise to lower-right (120 km/h)

    /** Analog dial vs. a digital 7-segment readout. */
    enum class Mode { ANALOG, DIGITAL }

    private var mode = Mode.ANALOG

    /** Switch between the analog dial and the digital readout. */
    fun setMode(mode: Mode) {
        if (this.mode != mode) {
            this.mode = mode
            invalidate()
        }
    }

    private val density = resources.displayMetrics.density

    // Neon palette for the glossy gauge look.
    private val neonBlue = Color.parseColor("#FF1E9BFF")
    private val neonCyan = Color.parseColor("#FF35E4FF")
    private val neonWhite = Color.parseColor("#FFEAFBFF")
    private val neonRed = Color.parseColor("#FFFF2E3A")
    private val neonGlow = Color.parseColor("#FF3FD2FF")

    // The arc's blue→cyan→white→red sweep shader, rebuilt when the size changes.
    private var arcShaderSize = 0

    private val neonArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val neonTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = neonWhite
    }
    private val neonNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonWhite
    }
    private val neonHubGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonBlue
    }
    private val neonHubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonWhite
    }
    private val needlePath = Path()

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = neonCyan
        textAlign = Paint.Align.CENTER
    }

    // GPS icon shown to the left of the speed number; tinted per draw.
    private val gpsIcon: Drawable? =
        ResourcesCompat.getDrawable(resources, R.drawable.ic_gps, context.theme)

    // Digital 7-segment readout: lit segments in white, "off" segments drawn as
    // a faint ghost so it reads like an old Casio LCD.
    private val segOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFFFF")
    }
    private val segOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1FFFFFFF") // ~12% white ghost
    }
    // Cyan variant used for the live readout on the neon analog gauge.
    private val segCyanOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = neonCyan
    }
    private val segCyanOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2235E4FF") // faint cyan ghost
    }
    private val segPath = Path()

    // Which of the 7 segments (a,b,c,d,e,f,g) are lit for each digit 0-9.
    //                                a      b      c      d      e      f      g
    private val segments = arrayOf(
        booleanArrayOf(true,  true,  true,  true,  true,  true,  false), // 0
        booleanArrayOf(false, true,  true,  false, false, false, false), // 1
        booleanArrayOf(true,  true,  false, true,  true,  false, true ), // 2
        booleanArrayOf(true,  true,  true,  true,  false, false, true ), // 3
        booleanArrayOf(false, true,  true,  false, false, true,  true ), // 4
        booleanArrayOf(true,  false, true,  true,  false, true,  true ), // 5
        booleanArrayOf(true,  false, true,  true,  true,  true,  true ), // 6
        booleanArrayOf(true,  true,  true,  false, false, false, false), // 7
        booleanArrayOf(true,  true,  true,  true,  true,  true,  true ), // 8
        booleanArrayOf(true,  true,  true,  true,  false, true,  true )  // 9
    )

    private val arcRect = RectF()

    init {
        // The soft glows rely on shadow layers, which are only honoured on a
        // software layer (they're a no-op under hardware acceleration).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        neonArcPaint.setShadowLayer(8f * density, 0f, 0f, neonGlow)
        neonTickPaint.setShadowLayer(4f * density, 0f, 0f, neonGlow)
        neonNeedlePaint.setShadowLayer(7f * density, 0f, 0f, neonWhite)
        neonHubGlowPaint.setShadowLayer(12f * density, 0f, 0f, neonBlue)
        segCyanOn.setShadowLayer(6f * density, 0f, 0f, neonCyan)
    }

    private var displayedSpeed = 0f
    private var animator: ValueAnimator? = null

    // Optional image-based analog gauge. If both a "speedo_dial" and a
    // "speedo_needle" drawable exist, the analog mode renders them instead of
    // the hand-drawn dial: the dial is drawn as a static background and the
    // needle drawable is rotated about the centre by the current speed.
    // The needle art is assumed square, centred, and pointing straight up at
    // rest. If either drawable is missing we fall back to the drawn dial.
    private var imagesResolved = false
    private var dialDrawable: Drawable? = null
    private var needleDrawable: Drawable? = null

    private fun ensureImagesLoaded() {
        if (imagesResolved) return
        imagesResolved = true
        val pkg = context.packageName
        val dialId = resources.getIdentifier("speedo_dial", "drawable", pkg)
        val needleId = resources.getIdentifier("speedo_needle", "drawable", pkg)
        if (dialId != 0) dialDrawable = ResourcesCompat.getDrawable(resources, dialId, context.theme)
        if (needleId != 0) needleDrawable = ResourcesCompat.getDrawable(resources, needleId, context.theme)
    }

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

    /** Build (once per size) the sweep shader so the arc runs blue→cyan→white→red. */
    private fun ensureArcShader(cx: Float, cy: Float) {
        val size = min(width, height)
        if (size == arcShaderSize && neonArcPaint.shader != null) return
        arcShaderSize = size
        val grad = SweepGradient(
            cx, cy,
            intArrayOf(neonBlue, neonBlue, neonCyan, neonWhite, neonRed, neonRed),
            floatArrayOf(0f, 0.12f, 0.34f, 0.48f, 0.64f, 1f)
        )
        // Rotate so the gradient's start aligns with the arc start (0 km/h).
        grad.setLocalMatrix(Matrix().apply { setRotate(startAngle, cx, cy) })
        neonArcPaint.shader = grad
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.ANALOG -> drawAnalog(canvas)
            Mode.DIGITAL -> drawDigital(canvas)
        }
    }

    private fun drawAnalog(canvas: Canvas) {
        ensureImagesLoaded()
        val dial = dialDrawable
        val needle = needleDrawable
        if (dial != null && needle != null) {
            drawImageGauge(canvas, dial, needle)
            return
        }

        drawNeonGauge(canvas)
    }

    private fun drawNeonGauge(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 10f * density
        ensureArcShader(cx, cy)

        // Glowing dial arc: blue → cyan → white → red.
        neonArcPaint.strokeWidth = r * 0.07f
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, neonArcPaint)

        // Fine ticks every 5 km/h; longer and brighter every 20.
        var value = 0
        while (value <= maxSpeed.toInt()) {
            val major = value % majorStep == 0
            val angle = angleFor(value.toFloat())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val rOuter = r * 0.90f
            val rInner = if (major) r * 0.78f else r * 0.84f
            neonTickPaint.strokeWidth = if (major) r * 0.018f else r * 0.010f
            neonTickPaint.alpha = if (major) 235 else 140
            canvas.drawLine(
                cx + cosA * rInner, cy + sinA * rInner,
                cx + cosA * rOuter, cy + sinA * rOuter,
                neonTickPaint
            )
            value += 5
        }

        // Tapered glowing needle with a small counterweight tail.
        val angle = angleFor(displayedSpeed)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val pCos = cos(angle + Math.PI / 2).toFloat()
        val pSin = sin(angle + Math.PI / 2).toFloat()
        val tipR = r * 0.72f
        val tailR = r * 0.14f
        val baseW = r * 0.035f
        needlePath.run {
            rewind()
            moveTo(cx + cosA * tipR, cy + sinA * tipR)     // tip
            lineTo(cx + pCos * baseW, cy + pSin * baseW)   // base side
            lineTo(cx - cosA * tailR, cy - sinA * tailR)   // counterweight tail
            lineTo(cx - pCos * baseW, cy - pSin * baseW)   // other base side
            close()
        }
        canvas.drawPath(needlePath, neonNeedlePaint)

        // Glowing hub.
        canvas.drawCircle(cx, cy, r * 0.10f, neonHubGlowPaint)
        canvas.drawCircle(cx, cy, r * 0.055f, neonHubPaint)

        // Live speed readout (GPS icon · digits · km/h), below the hub.
        drawSpeedReadout(
            canvas, cx, cy + r * 0.40f,
            maxDigitHeight = r * 0.26f, availWidth = r * 1.5f,
            onPaint = segCyanOn, offPaint = segCyanOff, unitColor = neonCyan, iconTint = neonCyan
        )
    }

    /**
     * Draw the live speed as a horizontal readout — a GPS icon, the 7-segment
     * digits, and a "km/h" label. The row is scaled to fit [availWidth] (capped
     * at [maxDigitHeight]) and centred on ([centerX], [centerY]).
     */
    private fun drawSpeedReadout(
        canvas: Canvas, centerX: Float, centerY: Float,
        maxDigitHeight: Float, availWidth: Float,
        onPaint: Paint, offPaint: Paint, unitColor: Int, iconTint: Int
    ) {
        val digits = displayedSpeed.toInt().coerceIn(0, 999).toString()
        val n = digits.length

        // Element sizes as multiples of the digit height (dh).
        val cellWf = 0.58f
        val gapf = 0.14f
        val iconf = 0.55f
        val spacef = 0.26f
        val unitTextf = 0.34f
        val digitsf = n * cellWf + (n - 1) * gapf

        unitPaint.color = unitColor
        unitPaint.textAlign = Paint.Align.LEFT
        unitPaint.textSize = 1f
        val unitf = unitTextf * unitPaint.measureText("km/h") // width at textSize 1

        // Solve dh so the whole row fits availWidth, capped at maxDigitHeight.
        val dh = min(availWidth / (iconf + spacef + digitsf + spacef + unitf), maxDigitHeight)

        val cellW = cellWf * dh
        val gap = gapf * dh
        val thickness = cellW * 0.16f
        val digitsW = n * cellW + (n - 1) * gap
        val iconSize = iconf * dh
        val space = spacef * dh
        unitPaint.textSize = unitTextf * dh
        val unitW = unitPaint.measureText("km/h")

        var x = centerX - (iconSize + space + digitsW + space + unitW) / 2f

        // GPS icon, vertically centred.
        gpsIcon?.let {
            it.setTint(iconTint)
            val iconTop = centerY - iconSize / 2f
            it.setBounds(x.toInt(), iconTop.toInt(), (x + iconSize).toInt(), (iconTop + iconSize).toInt())
            it.draw(canvas)
        }
        x += iconSize + space

        // Digits.
        val top = centerY - dh / 2f
        for (i in 0 until n) {
            val on = segments[digits[i] - '0']
            drawSevenSegment(canvas, x + i * (cellW + gap), top, cellW, dh, thickness, on, onPaint, offPaint)
        }
        x += digitsW + space

        // "km/h", left-aligned and vertically centred on the digits.
        val fm = unitPaint.fontMetrics
        canvas.drawText("km/h", x, centerY - (fm.ascent + fm.descent) / 2f, unitPaint)
    }

    /**
     * Image-based analog gauge: the dial drawable fills the (square) view and
     * the needle drawable is rotated about the centre to point at the current
     * speed. The needle source art is assumed to point straight up at rest, so
     * the rotation is the dial angle for the speed plus 90°.
     */
    private fun drawImageGauge(canvas: Canvas, dial: Drawable, needle: Drawable) {
        val size = min(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2

        dial.setBounds(left, top, left + size, top + size)
        dial.draw(canvas)

        val rotation = startAngle + sweepAngle * displayedSpeed / maxSpeed + 90f
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        needle.setBounds(left, top, left + size, top + size)
        needle.draw(canvas)
        canvas.restore()
    }

    /**
     * Big white 7-segment readout for digital mode, flanked by a GPS icon and a
     * "km/h" label, centred in the view.
     */
    private fun drawDigital(canvas: Canvas) {
        drawSpeedReadout(
            canvas, width / 2f, height / 2f,
            maxDigitHeight = height * 0.34f, availWidth = width * 0.92f,
            onPaint = segOnPaint, offPaint = segOffPaint,
            unitColor = Color.WHITE, iconTint = Color.WHITE
        )
    }

    /** Render one 7-segment digit inside the given cell, in the given colours. */
    private fun drawSevenSegment(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float, on: BooleanArray,
        onPaint: Paint, offPaint: Paint
    ) {
        val pad = t * 1.1f
        val xL = x + pad                 // left rail
        val xR = x + w - pad             // right rail
        val yT = y + pad                 // top rail
        val yM = y + h / 2f              // middle
        val yB = y + h - pad             // bottom rail

        // a top, b top-right, c bottom-right, d bottom, e bottom-left, f top-left, g middle
        horizontalSegment(canvas, xL, xR, yT, t, on[0], onPaint, offPaint)
        verticalSegment(canvas, xR, yT, yM, t, on[1], onPaint, offPaint)
        verticalSegment(canvas, xR, yM, yB, t, on[2], onPaint, offPaint)
        horizontalSegment(canvas, xL, xR, yB, t, on[3], onPaint, offPaint)
        verticalSegment(canvas, xL, yM, yB, t, on[4], onPaint, offPaint)
        verticalSegment(canvas, xL, yT, yM, t, on[5], onPaint, offPaint)
        horizontalSegment(canvas, xL, xR, yM, t, on[6], onPaint, offPaint)
    }

    private fun horizontalSegment(
        canvas: Canvas, x1: Float, x2: Float, y: Float, t: Float, on: Boolean,
        onPaint: Paint, offPaint: Paint
    ) {
        segPath.run {
            rewind()
            moveTo(x1, y)
            lineTo(x1 + t, y - t)
            lineTo(x2 - t, y - t)
            lineTo(x2, y)
            lineTo(x2 - t, y + t)
            lineTo(x1 + t, y + t)
            close()
        }
        canvas.drawPath(segPath, if (on) onPaint else offPaint)
    }

    private fun verticalSegment(
        canvas: Canvas, x: Float, y1: Float, y2: Float, t: Float, on: Boolean,
        onPaint: Paint, offPaint: Paint
    ) {
        segPath.run {
            rewind()
            moveTo(x, y1)
            lineTo(x + t, y1 + t)
            lineTo(x + t, y2 - t)
            lineTo(x, y2)
            lineTo(x - t, y2 - t)
            lineTo(x - t, y1 + t)
            close()
        }
        canvas.drawPath(segPath, if (on) onPaint else offPaint)
    }
}
