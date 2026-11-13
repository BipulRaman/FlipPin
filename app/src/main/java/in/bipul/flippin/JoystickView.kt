package `in`.bipul.flippin

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A 360° joystick. While dragged it reports the bearing (degrees clockwise from north, which is up)
 * and whether it is outside the centre dead zone; releasing stops movement.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onMove: ((bearing: Double, moving: Boolean) -> Unit)? = null
    /** A single keyboard or accessibility step in one compass direction. */
    var onStep: ((bearing: Double) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val knobRadius = 22 * density
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.background) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = context.getColor(R.color.border)
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.getColor(R.color.accent)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        alpha = 90
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val knobEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = context.getColor(R.color.surface)
    }
    private var knobX = 0f
    private var knobY = 0f
    private var bearing = 0.0
    private var dragging = false
    private var returning: ValueAnimator? = null

    init {
        isFocusable = true
    }

    private val baseRadius get() = min(width, height) / 2f - density
    private val travel get() = (baseRadius - knobRadius - 2 * density).coerceAtLeast(1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (DEFAULT_SIZE_DP * density).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = baseRadius
        canvas.drawCircle(centerX, centerY, radius, basePaint)
        canvas.drawCircle(centerX, centerY, radius, if (isFocused) focusPaint else ringPaint)
        val labelDistance = radius - 11 * density
        val baseline = -(labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText("N", centerX, centerY - labelDistance + baseline, labelPaint)
        canvas.drawText("S", centerX, centerY + labelDistance + baseline, labelPaint)
        canvas.drawText("E", centerX + labelDistance, centerY + baseline, labelPaint)
        canvas.drawText("W", centerX - labelDistance, centerY + baseline, labelPaint)
        if (knobX != 0f || knobY != 0f) {
            canvas.drawLine(centerX, centerY, centerX + knobX, centerY + knobY, trailPaint)
        }
        canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius, knobPaint)
        canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius, knobEdgePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Keep the surrounding scroll view from taking over vertical drags.
                parent?.requestDisallowInterceptTouchEvent(true)
                returning?.cancel()
                dragging = true
                isPressed = true
                drag(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) drag(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                performClick()
                release()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun drag(x: Float, y: Float) {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val distance = hypot(dx, dy)
        val scale = if (distance > travel) travel / distance else 1f
        knobX = dx * scale
        knobY = dy * scale
        if (distance > 0f) bearing = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360.0) % 360.0
        invalidate()
        onMove?.invoke(bearing, distance / travel > DEAD_ZONE)
    }

    /** Reports that movement stopped and springs the knob back to the centre. */
    fun release() {
        if (!dragging) return
        dragging = false
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        onMove?.invoke(bearing, false)
        val fromX = knobX
        val fromY = knobY
        returning = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = RETURN_MS
            addUpdateListener {
                val fraction = it.animatedValue as Float
                knobX = fromX * fraction
                knobY = fromY * fraction
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        returning?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = STEPS.firstOrNull { it.keyCode == keyCode } ?: return super.onKeyDown(keyCode, event)
        // One step per press: auto-repeat would move faster than any real pace.
        if (event.repeatCount == 0) onStep?.invoke(step.bearing)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        for (step in STEPS) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                step.action, context.getString(step.label, STEP_METERS.roundToInt())
            ))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = STEPS.firstOrNull { it.action == action }
            ?: return super.performAccessibilityAction(action, arguments)
        onStep?.invoke(step.bearing)
        return true
    }

    private class Step(val action: Int, val label: Int, val keyCode: Int, val bearing: Double)

    private companion object {
        const val DEFAULT_SIZE_DP = 128
        const val RETURN_MS = 120L
        const val DEAD_ZONE = 0.12f
        val STEPS = listOf(
            Step(R.id.move_north, R.string.move_north, KeyEvent.KEYCODE_DPAD_UP, 0.0),
            Step(R.id.move_east, R.string.move_east, KeyEvent.KEYCODE_DPAD_RIGHT, 90.0),
            Step(R.id.move_south, R.string.move_south, KeyEvent.KEYCODE_DPAD_DOWN, 180.0),
            Step(R.id.move_west, R.string.move_west, KeyEvent.KEYCODE_DPAD_LEFT, 270.0)
        )
    }
}
