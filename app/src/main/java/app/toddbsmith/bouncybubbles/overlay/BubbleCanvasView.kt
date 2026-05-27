package app.toddbsmith.bouncybubbles.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import app.toddbsmith.bouncybubbles.physics.Bubble
import app.toddbsmith.bouncybubbles.physics.BubblePalette
import app.toddbsmith.bouncybubbles.physics.PhysicsEngine
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The actual overlay surface.
 *
 * Drawn into a WindowManager window with TYPE_APPLICATION_OVERLAY. We intercept
 * touches that land on a bubble or the dismiss zone; everything else falls
 * through to the app underneath via FLAG_NOT_FOCUSABLE + ignoring the event.
 *
 * Driven by Choreographer so we tick once per vsync. Gravity comes from the
 * accelerometer, remapped to screen coordinates.
 */
@SuppressLint("ViewConstructor")
class BubbleCanvasView(
    context: Context,
    private val initialCount: Int,
    private val radiusRangeDp: ClosedFloatingPointRange<Float>,
    gravityScale: Float,
    bounciness: Float,
) : View(context), SensorEventListener, Choreographer.FrameCallback {

    private val engine = PhysicsEngine().apply {
        restitution = bounciness.coerceIn(0.5f, 0.95f)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val choreographer = Choreographer.getInstance()

    // Tilt -> gravity. 9.81 m/s^2 maps to (gravityScale * pxPerMeter) px/s^2 on screen.
    // Phones are ~5-6 inches tall; calling 1m = 800 px feels right after experiments.
    private val pxPerMeter = 800f * gravityScale.coerceIn(0f, 4f)

    // Low-pass filter the raw accelerometer to extract the gravity-only component.
    // Alpha = 0.85 is a standard "isolate gravity" filter constant from the docs.
    private val gravityLP = FloatArray(3)
    private val gravityAlpha = 0.85f

    // For drag fling — keep a tiny ring buffer of (t, x, y) samples per pointer.
    private data class TouchSample(val t: Long, val x: Float, val y: Float)
    private val touchHistory = ArrayDeque<TouchSample>(8)

    // Currently dragged bubble (single-touch model — keep it simple).
    private var draggedBubble: Bubble? = null

    // Dismiss zone state.
    private val dismissBaseRadiusPx = dp(56f)
    private val dismissActiveRadiusPx = dp(96f)
    private val dismissTriggerDistPx = dp(72f)   // pop when bubble center within this
    private val dismissProximityPx = dp(200f)    // grow when held bubble within this
    private var dismissCx: Float = 0f
    private var dismissCy: Float = 0f
    private var dismissGrow: Float = 0f          // 0..1 animated value

    // Paint objects — preallocated for the hot path.
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(60, 0, 0, 0)
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
    }
    private val dismissPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
    }
    private val dismissGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
    }

    private var lastFrameTimeNanos: Long = 0L
    private var attached: Boolean = false

    // Cap; tap-to-spawn ignored beyond this.
    private val maxBubbles = 25

    init {
        // Spawn the initial population once size is known (deferred to first layout).
        post { seedInitialBubbles() }
    }

    private fun seedInitialBubbles() {
        if (engine.bubbles.isNotEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rMin = dp(radiusRangeDp.start)
        val rMax = dp(radiusRangeDp.endInclusive)
        repeat(initialCount.coerceIn(1, maxBubbles)) {
            val r = Random.nextFloat() * (rMax - rMin) + rMin
            val pair = BubblePalette.random()
            engine.addBubble(
                Bubble(
                    x = Random.nextFloat() * (w - 2f * r) + r,
                    y = Random.nextFloat() * (h * 0.6f - 2f * r) + r,
                    vx = (Random.nextFloat() - 0.5f) * 200f,
                    vy = (Random.nextFloat() - 0.5f) * 200f,
                    radius = r,
                    colorTop = pair.top,
                    colorBottom = pair.bottom,
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.width = w.toFloat()
        engine.height = h.toFloat()
        dismissCx = w * 0.5f
        dismissCy = h - dp(96f)
        if (engine.bubbles.isEmpty()) seedInitialBubbles()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (accelerometer != null) {
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }
        lastFrameTimeNanos = 0L
        choreographer.postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        sensorManager.unregisterListener(this)
        choreographer.removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    // ---------------- Frame loop ----------------

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        val last = lastFrameTimeNanos
        lastFrameTimeNanos = frameTimeNanos
        val dt = if (last == 0L) 1f / 60f
        else ((frameTimeNanos - last) / 1_000_000_000f).coerceAtMost(1f / 30f)

        // Animate dismiss zone grow toward target (1 if a bubble is near, else 0).
        val target = if (shouldGrowDismiss()) 1f else 0f
        // ~140ms ease.
        dismissGrow += (target - dismissGrow) * (dt * 8f).coerceAtMost(1f)

        engine.step(dt)
        invalidate()
        choreographer.postFrameCallback(this)
    }

    private fun shouldGrowDismiss(): Boolean {
        val held = draggedBubble ?: return false
        return hypot(held.x - dismissCx, held.y - dismissCy) < dismissProximityPx
    }

    // ---------------- Sensor ----------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Isolate gravity component with low-pass filter.
        gravityLP[0] = gravityAlpha * gravityLP[0] + (1 - gravityAlpha) * event.values[0]
        gravityLP[1] = gravityAlpha * gravityLP[1] + (1 - gravityAlpha) * event.values[1]
        gravityLP[2] = gravityAlpha * gravityLP[2] + (1 - gravityAlpha) * event.values[2]

        // Sensor frame (when phone is face up, top edge "up"):
        //   +X = right of device, +Y = top of device, +Z = out of screen.
        // Screen frame:
        //   +X = right, +Y = DOWN.
        //
        // The accelerometer measures the support force, which is OPPOSITE to
        // gravity. Held upright in portrait, gravity points to the bottom edge,
        // and the sensor reads ~+9.8 on Y (top of device). To get the gravity
        // *vector* in sensor frame we negate. Then we remap device X/Y to
        // screen X/Y via rotation.
        //
        // Net mapping for ROTATION_0 (portrait):
        //   screen_gx = -sensor_x   (tilt right -> sensor_x > 0 -> roll left? no:
        //                            held upright tilted slightly right, the right
        //                            edge moves down; gravity in screen frame
        //                            should pull right. sensor_x at rest while
        //                            tilted right is NEGATIVE because the support
        //                            force now has a leftward component. So -sx > 0
        //                            -> pull right. correct.)
        //   screen_gy = +sensor_y   (held upright: sensor_y ~ +9.8 -> screen_gy
        //                            +9.8 -> bubbles pull DOWN. correct.)
        //
        // Wait — those signs only match if screen down (= +Y on screen) corresponds
        // to "where gravity pulls when phone is upright." For an upright phone,
        // real-world down = the bottom edge of the phone = screen +Y. Sensor_y is
        // measured along the device's long axis with +Y toward the top. Support
        // force when at rest = pointing along +Y_device with magnitude 9.8. The
        // gravity vector itself = -support = pointing along -Y_device. So gravity
        // in screen frame for portrait = -sensor_y mapped to screen-down. screen-down
        // is +Y_screen, and -Y_device = +Y_screen direction (because device top is
        // screen top = small Y). So screen_gy = -(-sensor_y) = +sensor_y. Yes.
        // display is non-null since the View is attached to a window.
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val sx = gravityLP[0]
        val sy = gravityLP[1]

        // For each rotation, derive (screen_gx, screen_gy) from (sx, sy).
        val screenGx: Float
        val screenGy: Float
        when (rotation) {
            Surface.ROTATION_0 -> {       // portrait
                screenGx = -sx
                screenGy = sy
            }
            Surface.ROTATION_90 -> {      // landscape, top of device on the LEFT
                screenGx = sy
                screenGy = sx
            }
            Surface.ROTATION_180 -> {     // upside-down portrait
                screenGx = sx
                screenGy = -sy
            }
            Surface.ROTATION_270 -> {     // landscape, top of device on the RIGHT
                screenGx = -sy
                screenGy = -sx
            }
            else -> {
                screenGx = -sx
                screenGy = sy
            }
        }

        // sx/sy are in m/s^2 once the LPF settles. Scale to px/s^2.
        engine.gravityX = screenGx * pxPerMeter / 9.81f
        engine.gravityY = screenGy * pxPerMeter / 9.81f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------- Touch ----------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val now = SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = bubbleAt(x, y)
                if (b != null) {
                    draggedBubble = b
                    b.beingDragged = true
                    b.dragOffsetX = b.x - x
                    b.dragOffsetY = b.y - y
                    b.targetX = x + b.dragOffsetX
                    b.targetY = y + b.dragOffsetY
                    touchHistory.clear()
                    touchHistory.addLast(TouchSample(now, x, y))
                    return true
                }
                // Tap empty space — spawn a bubble if we have room.
                if (engine.bubbles.size < maxBubbles) {
                    spawnBubbleAt(x, y)
                    return true
                }
                return false // pass through
            }
            MotionEvent.ACTION_MOVE -> {
                val b = draggedBubble ?: return false
                b.targetX = x + b.dragOffsetX
                b.targetY = y + b.dragOffsetY
                touchHistory.addLast(TouchSample(now, x, y))
                while (touchHistory.size > 6) touchHistory.removeFirst()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = draggedBubble ?: return false
                b.beingDragged = false
                draggedBubble = null

                // Dismiss?
                if (hypot(b.x - dismissCx, b.y - dismissCy) < dismissTriggerDistPx) {
                    b.popping = true
                    b.popProgress = 0f
                    b.vx = 0f
                    b.vy = 0f
                    return true
                }

                // Fling — compute velocity from the most recent samples.
                if (touchHistory.size >= 2) {
                    val first = touchHistory.first()
                    val last = touchHistory.last()
                    val dtSec = ((last.t - first.t).coerceAtLeast(1L)) / 1000f
                    val flingVx = (last.x - first.x) / dtSec
                    val flingVy = (last.y - first.y) / dtSec
                    // Clamp to keep things sane.
                    b.vx = flingVx.coerceIn(-4000f, 4000f)
                    b.vy = flingVy.coerceIn(-4000f, 4000f)
                }
                touchHistory.clear()
                return true
            }
        }
        return false
    }

    private fun bubbleAt(x: Float, y: Float): Bubble? {
        // Iterate front-to-back so the top-drawn bubble wins.
        for (i in engine.bubbles.indices.reversed()) {
            val b = engine.bubbles[i]
            if (b.popping) continue
            val dx = b.x - x
            val dy = b.y - y
            if (dx * dx + dy * dy <= b.radius * b.radius) return b
        }
        return null
    }

    private fun spawnBubbleAt(x: Float, y: Float) {
        val rMin = dp(radiusRangeDp.start)
        val rMax = dp(radiusRangeDp.endInclusive)
        val r = Random.nextFloat() * (rMax - rMin) + rMin
        val pair = BubblePalette.random()
        engine.addBubble(
            Bubble(
                x = x,
                y = y,
                vx = (Random.nextFloat() - 0.5f) * 300f,
                vy = (Random.nextFloat() - 0.5f) * 300f - 200f, // slight upward pop
                radius = r,
                colorTop = pair.top,
                colorBottom = pair.bottom,
            )
        )
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDismissZone(canvas)

        val list = engine.bubbles
        var i = 0
        val n = list.size
        while (i < n) {
            drawBubble(canvas, list[i])
            i++
        }
    }

    private fun drawDismissZone(canvas: Canvas) {
        if (dismissGrow <= 0.001f && draggedBubble == null) return
        val r = dismissBaseRadiusPx + (dismissActiveRadiusPx - dismissBaseRadiusPx) * dismissGrow
        // Glow halo.
        canvas.drawCircle(dismissCx, dismissCy, r * 1.4f, dismissGlowPaint)
        // Solid ring.
        canvas.drawCircle(dismissCx, dismissCy, r, dismissPaint)
        // Cross icon.
        val cross = r * 0.4f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(3f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(dismissCx - cross, dismissCy - cross, dismissCx + cross, dismissCy + cross, p)
        canvas.drawLine(dismissCx - cross, dismissCy + cross, dismissCx + cross, dismissCy - cross, p)
    }

    private fun drawBubble(canvas: Canvas, b: Bubble) {
        val scale = if (b.popping) (1f - b.popProgress).coerceAtLeast(0f) else 1f
        if (scale <= 0.01f) return
        val r = b.radius * scale

        // Radial gradient offset toward upper-left to fake a light source.
        val gx = b.x - r * 0.35f
        val gy = b.y - r * 0.35f
        bubblePaint.shader = RadialGradient(
            gx, gy, r * 1.4f,
            b.colorTop, b.colorBottom,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(b.x, b.y, r, bubblePaint)
        bubblePaint.shader = null

        // Outer dark ring.
        canvas.drawCircle(b.x, b.y, r - dp(0.5f), ringPaint)

        // Specular highlight — tiny white dot upper-left.
        val hx = b.x - r * 0.45f
        val hy = b.y - r * 0.45f
        canvas.drawCircle(hx, hy, r * 0.18f, highlightPaint)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
