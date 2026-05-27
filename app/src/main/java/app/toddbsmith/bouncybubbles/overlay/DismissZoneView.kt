package app.toddbsmith.bouncybubbles.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * Bottom-of-screen dismiss target. Only attached to the WindowManager while a
 * bubble drag is in progress. The service feeds it [setActive] (whether the
 * dragged bubble is close enough to "capture") which animates a grow/glow,
 * and [animate] each frame to advance that interpolation.
 *
 * This view is non-interactive (FLAG_NOT_TOUCHABLE on its window params) — it
 * exists only to visually communicate the dismiss target. Capture detection
 * happens in the service against geometry.
 */
@SuppressLint("ViewConstructor")
class DismissZoneView(context: Context) : View(context) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
    }
    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 80, 80)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(3f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val baseRadiusPx = dp(48f)
    private val activeRadiusPx = dp(68f)

    private var growT: Float = 0f       // 0..1
    private var activeTarget: Float = 0f

    fun setActive(active: Boolean) {
        activeTarget = if (active) 1f else 0f
    }

    /** Advance the grow animation; call once per frame from the service. */
    fun animate(dt: Float) {
        val k = (dt * 8f).coerceAtMost(1f)
        val newT = growT + (activeTarget - growT) * k
        if (kotlin.math.abs(newT - growT) > 0.001f) {
            growT = newT
            invalidate()
        } else {
            growT = newT
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val r = baseRadiusPx + (activeRadiusPx - baseRadiusPx) * growT

        // Halo behind.
        canvas.drawCircle(cx, cy, r * 1.6f, glowPaint)
        if (growT > 0.01f) {
            canvas.drawCircle(cx, cy, r * 1.3f, activeGlowPaint)
        }
        canvas.drawCircle(cx, cy, r, basePaint)

        // X glyph.
        val cross = r * 0.42f
        canvas.drawLine(cx - cross, cy - cross, cx + cross, cy + cross, crossPaint)
        canvas.drawLine(cx - cross, cy + cross, cx + cross, cy - cross, crossPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        /** Total view square size in dp. Sized for the largest glow at the active state. */
        const val VIEW_SIZE_DP = 220f
        /** Capture radius in dp — bubble's center within this distance of zone center triggers dismiss. */
        const val CAPTURE_RADIUS_DP = 72f
        /** Activation radius — bubble within this distance starts the zone growing/glowing. */
        const val PROXIMITY_RADIUS_DP = 200f
        /** Y offset from screen bottom to zone center, in dp. */
        const val BOTTOM_OFFSET_DP = 110f
    }
}
