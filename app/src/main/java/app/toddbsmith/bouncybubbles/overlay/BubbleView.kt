package app.toddbsmith.bouncybubbles.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import app.toddbsmith.bouncybubbles.physics.Bubble
import kotlin.math.hypot

/**
 * A single-bubble overlay view. Sized to exactly fit the bubble (2*radius +
 * a tiny anti-alias slack) and re-positioned every frame by the service via
 * WindowManager.updateViewLayout. The view itself never moves its own
 * x/y — the WindowManager params do.
 *
 * Touch handling: down anywhere inside the view starts a drag (the view is
 * bubble-sized, so anywhere-inside == on-bubble). The view consumes its own
 * touch events; touches outside this view fall through to whatever is below
 * (the OS / app underneath) because this is a small window, not a fullscreen
 * one, and we do NOT set FLAG_WATCH_OUTSIDE_TOUCH.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    val bubble: Bubble,
    private val callbacks: Callbacks,
) : View(context) {

    interface Callbacks {
        /** Drag start at raw screen coordinates. */
        fun onBubbleDragStart(bubble: Bubble, rawX: Float, rawY: Float)
        /** Drag move at raw screen coordinates. */
        fun onBubbleDragMove(bubble: Bubble, rawX: Float, rawY: Float)
        /** Drag end with the recent samples used for fling velocity. */
        fun onBubbleDragEnd(bubble: Bubble, vxPxPerSec: Float, vyPxPerSec: Float, canceled: Boolean)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(60, 0, 0, 0)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
    }
    private val dragRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.argb(200, 255, 255, 255)
    }

    private data class TouchSample(val t: Long, val rx: Float, val ry: Float)
    private val touchHistory = ArrayDeque<TouchSample>(8)
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private val touchSlopPx = dp(6f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The bubble's logical center is at view-center (= radius+slack, radius+slack).
        // We draw using the view's own coordinate frame.
        val baseR = bubble.radius
        val cx = baseR + SLACK_PX
        val cy = baseR + SLACK_PX
        val scale = bubble.scale
        if (scale <= 0.01f) return
        val r = baseR * scale

        val gx = cx - r * 0.35f
        val gy = cy - r * 0.35f
        bubblePaint.shader = RadialGradient(
            gx, gy, r * 1.4f,
            bubble.colorTop, bubble.colorBottom,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, bubblePaint)
        bubblePaint.shader = null

        // Outer dark ring.
        canvas.drawCircle(cx, cy, r - dp(0.5f), ringPaint)

        // Specular highlight.
        val hx = cx - r * 0.45f
        val hy = cy - r * 0.45f
        canvas.drawCircle(hx, hy, r * 0.18f, highlightPaint)

        // White ring while being dragged for tactile feedback.
        if (bubble.beingDragged) {
            canvas.drawCircle(cx, cy, r + dp(2f), dragRingPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // We need raw screen coords because the bubble physics is in screen
        // space and this view's own x/y doesn't tell us where on screen it is
        // (the WindowManager params own that).
        val rx = event.rawX
        val ry = event.rawY
        val now = SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = rx
                downRawY = ry
                moved = false
                isDragging = true
                touchHistory.clear()
                touchHistory.addLast(TouchSample(now, rx, ry))
                callbacks.onBubbleDragStart(bubble, rx, ry)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                if (!moved && hypot(rx - downRawX, ry - downRawY) > touchSlopPx) {
                    moved = true
                }
                touchHistory.addLast(TouchSample(now, rx, ry))
                while (touchHistory.size > 6) touchHistory.removeFirst()
                callbacks.onBubbleDragMove(bubble, rx, ry)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                val canceled = event.actionMasked == MotionEvent.ACTION_CANCEL
                val (fvx, fvy) = computeFlingVelocity()
                touchHistory.clear()
                callbacks.onBubbleDragEnd(bubble, fvx, fvy, canceled)
                return true
            }
        }
        return false
    }

    private fun computeFlingVelocity(): Pair<Float, Float> {
        if (touchHistory.size < 2) return 0f to 0f
        val first = touchHistory.first()
        val last = touchHistory.last()
        val dtSec = ((last.t - first.t).coerceAtLeast(1L)) / 1000f
        val vx = (last.rx - first.rx) / dtSec
        val vy = (last.ry - first.ry) / dtSec
        return vx.coerceIn(-4000f, 4000f) to vy.coerceIn(-4000f, 4000f)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        /** Tiny extra pixel padding around the bubble for anti-aliasing slack. */
        const val SLACK_PX = 2
    }
}
