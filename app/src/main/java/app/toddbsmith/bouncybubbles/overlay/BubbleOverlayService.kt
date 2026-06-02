package app.toddbsmith.bouncybubbles.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.core.app.NotificationCompat
import app.toddbsmith.bouncybubbles.MainActivity
import app.toddbsmith.bouncybubbles.R
import app.toddbsmith.bouncybubbles.physics.Bubble
import app.toddbsmith.bouncybubbles.physics.BubblePalette
import app.toddbsmith.bouncybubbles.physics.PhysicsEngine
import app.toddbsmith.bouncybubbles.physics.StaticObstacle
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Foreground service that owns the bubble overlay.
 *
 * Architecture (Messenger chat-head style):
 *  - Every bubble is its own tiny WindowManager view (BubbleView), sized to
 *    fit the bubble and positioned by absolute x/y in WindowManager.LayoutParams.
 *    Touches outside any bubble window fall through to the OS / app below —
 *    the user can use the phone normally.
 *  - The FAB is a single small window (FabView), draggable, with an expandable
 *    menu. The FAB is also a static physics obstacle so bubbles ricochet off it.
 *  - The dismiss zone is attached only while a bubble is being dragged.
 *
 * The Choreographer loop lives in this service. Each frame:
 *  1. engine.step(dt) — moves bubbles
 *  2. for each bubble: updateViewLayout to its new position (top-left corner =
 *     bubble center - radius - slack)
 *  3. dismiss zone animation tick
 *  4. invalidate bubbles whose appearance changed (dragged ring, spawn-in,
 *     pop-out)
 *
 * Window coordinate-system reminder: with `gravity = TOP|START` and
 * `flags |= FLAG_LAYOUT_NO_LIMITS`, `params.x` and `params.y` are raw
 * top-left-of-window in screen pixels (NOT density-corrected). Some
 * documentation suggests these are "extra" offsets; with NO_LIMITS they're
 * absolute.
 */
class BubbleOverlayService : Service(),
    SensorEventListener,
    Choreographer.FrameCallback,
    BubbleView.Callbacks,
    FabView.Callbacks {

    companion object {
        const val EXTRA_INITIAL_COUNT = "extra_initial_count"
        const val EXTRA_RADIUS_MIN_DP = "extra_radius_min_dp"
        const val EXTRA_RADIUS_MAX_DP = "extra_radius_max_dp"
        const val EXTRA_GRAVITY_SCALE = "extra_gravity_scale"
        const val EXTRA_BOUNCINESS = "extra_bounciness"

        const val ACTION_STOP = "app.toddbsmith.bouncybubbles.STOP"

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "bouncy_bubbles_overlay"

        /** Cap on simultaneous bubbles. */
        private const val MAX_BUBBLES = 25

        @Volatile
        var running: Boolean = false
            private set
    }

    // ----- Tuning loaded from intent -----
    private var radiusMinDp: Float = 36f
    private var radiusMaxDp: Float = 60f

    // ----- Core systems -----
    private lateinit var windowManager: WindowManager
    private lateinit var engine: PhysicsEngine
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var attached = false
    private var lastFrameTimeNanos: Long = 0L

    // ----- Screen metrics -----
    private var screenW: Int = 0
    private var screenH: Int = 0

    // ----- Bubble windows -----
    private data class BubbleWindow(
        val view: BubbleView,
        val params: WindowManager.LayoutParams,
    )
    private val bubbleWindows: MutableMap<Int, BubbleWindow> = HashMap()

    // ----- FAB window -----
    private var fabView: FabView? = null
    private var fabParams: WindowManager.LayoutParams? = null
    /** Current FAB center in screen px. Tracks LayoutParams + view-internal fabOffset. */
    private var fabCenterX: Float = 0f
    private var fabCenterY: Float = 0f
    private val fabObstacle = StaticObstacle(0f, 0f, 0f)
    private var fabSnapAnimator: ValueAnimator? = null
    private var fabDragInProgress = false

    // ----- Dismiss zone window -----
    private var dismissView: DismissZoneView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var dismissCenterX: Float = 0f
    private var dismissCenterY: Float = 0f
    private var draggedBubble: Bubble? = null

    // ----- Sensor -----
    private val gravityLP = FloatArray(3)
    private val gravityAlpha = 0.85f
    private var pxPerMeter = 800f

    // ----- Service lifecycle -----

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        engine = PhysicsEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (!attached) {
            setUp(intent)
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun setUp(intent: Intent?) {
        val initialCount = intent?.getIntExtra(EXTRA_INITIAL_COUNT, 6) ?: 6
        radiusMinDp = intent?.getFloatExtra(EXTRA_RADIUS_MIN_DP, 36f) ?: 36f
        radiusMaxDp = intent?.getFloatExtra(EXTRA_RADIUS_MAX_DP, 60f) ?: 60f
        val gravityScale = (intent?.getFloatExtra(EXTRA_GRAVITY_SCALE, 1.0f) ?: 1.0f)
            .coerceIn(0f, 4f)
        val bounciness = (intent?.getFloatExtra(EXTRA_BOUNCINESS, 0.85f) ?: 0.85f)
            .coerceIn(0.5f, 0.95f)
        engine.restitution = bounciness
        pxPerMeter = 800f * gravityScale

        refreshScreenMetrics()

        // Add the FAB first so its obstacle is registered before bubbles spawn.
        addFabWindow()
        engine.obstacles.add(fabObstacle)
        updateFabObstacleFromScreenPosition()

        // Seed bubbles.
        seedBubbles(initialCount.coerceIn(1, MAX_BUBBLES))

        // Start the frame loop.
        attached = true
        lastFrameTimeNanos = 0L
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        choreographer.postFrameCallback(this)
    }

    private fun tearDown() {
        if (!attached && bubbleWindows.isEmpty() && fabView == null && dismissView == null) {
            running = false
            return
        }
        attached = false
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) { /* ignore */ }
        choreographer.removeFrameCallback(this)
        fabSnapAnimator?.cancel()
        fabSnapAnimator = null

        for (w in bubbleWindows.values) safeRemove(w.view)
        bubbleWindows.clear()
        engine.bubbles.clear()
        engine.obstacles.clear()

        fabView?.let { safeRemove(it) }
        fabView = null
        fabParams = null
        dismissView?.let { safeRemove(it) }
        dismissView = null
        dismissParams = null
        draggedBubble = null
        running = false
    }

    private fun safeRemove(v: View) {
        try {
            windowManager.removeView(v)
        } catch (_: IllegalArgumentException) {
            // already removed
        }
    }

    // ----- Screen metrics -----

    private fun refreshScreenMetrics() {
        var insetL = 0; var insetT = 0; var insetR = 0; var insetB = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds: Rect = metrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
            // Keep bubbles out of the status bar + navigation bar (and display cutout)
            // so they never settle where the system swallows the touch.
            val ins = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetL = ins.left; insetT = ins.top; insetR = ins.right; insetB = ins.bottom
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val pt = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(pt)
            screenW = pt.x
            screenH = pt.y
        }
        engine.width = screenW.toFloat()
        engine.height = screenH.toFloat()
        engine.insetLeft = insetL.toFloat()
        engine.insetTop = insetT.toFloat()
        engine.insetRight = insetR.toFloat()
        engine.insetBottom = insetB.toFloat()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshScreenMetrics()
        // Re-snap the FAB to the new right edge if it was on an edge.
        snapFabToNearestEdge(animate = false)
    }

    // ----- Bubble windows -----

    private fun seedBubbles(count: Int) {
        repeat(count) {
            spawnBubbleAtRandom()
        }
    }

    private fun spawnBubbleAtRandom() {
        if (engine.bubbles.size >= MAX_BUBBLES) return
        val rMin = dp(radiusMinDp)
        val rMax = dp(radiusMaxDp)
        val r = Random.nextFloat() * (rMax - rMin) + rMin
        val pair = BubblePalette.random()
        // Spawn near top center with random offset.
        val spawnX = (screenW * 0.5f) + (Random.nextFloat() - 0.5f) * dp(120f)
        val spawnY = dp(96f) + Random.nextFloat() * dp(120f)
        val bubble = Bubble(
            x = spawnX.coerceIn(r, screenW - r),
            y = spawnY.coerceIn(r, screenH - r),
            vx = (Random.nextFloat() - 0.5f) * 200f,
            vy = (Random.nextFloat() - 0.5f) * 200f,
            radius = r,
            colorTop = pair.top,
            colorBottom = pair.bottom,
        )
        bubble.spawnProgress = 0f
        addBubbleWindow(bubble)
    }

    private fun addBubbleWindow(bubble: Bubble) {
        engine.addBubble(bubble)
        val size = (bubble.radius * 2f).toInt() + 2 * BubbleView.SLACK_PX
        val view = BubbleView(this, bubble, this)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubble.x - bubble.radius - BubbleView.SLACK_PX).toInt()
            y = (bubble.y - bubble.radius - BubbleView.SLACK_PX).toInt()
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            engine.removeBubble(bubble)
            return
        }
        bubbleWindows[bubble.id] = BubbleWindow(view, params)
    }

    private fun removeBubbleWindow(bubble: Bubble) {
        val win = bubbleWindows.remove(bubble.id) ?: return
        safeRemove(win.view)
    }

    // ----- FAB window -----

    private fun addFabWindow() {
        val view = FabView(this, this)
        val params = WindowManager.LayoutParams(
            view.viewWidthPx, view.viewHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        // Default position: 24dp from right edge, vertically centered.
        val margin = dp(24f)
        val targetCx = screenW - margin - dp(FabView.FAB_OBSTACLE_RADIUS_DP - 4f)
        val targetCy = screenH * 0.5f
        params.x = (targetCx - view.fabOffsetX).toInt()
        params.y = (targetCy - view.fabOffsetY).toInt()
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }
        fabView = view
        fabParams = params
        fabCenterX = targetCx
        fabCenterY = targetCy
    }

    private fun positionFabAtCenter(cx: Float, cy: Float) {
        val view = fabView ?: return
        val params = fabParams ?: return
        // Clamp center to keep the FAB on-screen.
        val r = dp(FabView.FAB_OBSTACLE_RADIUS_DP)
        val clampedCx = cx.coerceIn(r, screenW - r)
        val clampedCy = cy.coerceIn(dp(40f) + r, screenH - dp(40f) - r)
        params.x = (clampedCx - view.fabOffsetX).toInt()
        params.y = (clampedCy - view.fabOffsetY).toInt()
        fabCenterX = clampedCx
        fabCenterY = clampedCy
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) { /* ignore */ }
        updateFabObstacleFromScreenPosition()
    }

    private fun updateFabObstacleFromScreenPosition() {
        fabObstacle.x = fabCenterX
        fabObstacle.y = fabCenterY
        fabObstacle.radius = dp(FabView.FAB_OBSTACLE_RADIUS_DP)
    }

    private fun snapFabToNearestEdge(animate: Boolean = true) {
        if (fabView == null) return
        val r = dp(FabView.FAB_OBSTACLE_RADIUS_DP)
        val edgeMargin = dp(16f)
        val targetCx = if (fabCenterX < screenW * 0.5f) r + edgeMargin else screenW - r - edgeMargin
        val targetCy = fabCenterY.coerceIn(dp(80f) + r, screenH - dp(80f) - r)
        if (!animate) {
            positionFabAtCenter(targetCx, targetCy)
            return
        }
        val startX = fabCenterX
        val startY = fabCenterY
        fabSnapAnimator?.cancel()
        fabSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val cx = startX + (targetCx - startX) * t
                val cy = startY + (targetCy - startY) * t
                positionFabAtCenter(cx, cy)
            }
            start()
        }
    }

    // ----- FabView.Callbacks -----

    private var fabDragOffsetX = 0f
    private var fabDragOffsetY = 0f

    override fun onFabDragStart(rawX: Float, rawY: Float) {
        fabDragInProgress = true
        fabSnapAnimator?.cancel()
        fabDragOffsetX = fabCenterX - rawX
        fabDragOffsetY = fabCenterY - rawY
    }

    override fun onFabDragMove(rawX: Float, rawY: Float) {
        positionFabAtCenter(rawX + fabDragOffsetX, rawY + fabDragOffsetY)
    }

    override fun onFabDragEnd() {
        fabDragInProgress = false
        snapFabToNearestEdge(animate = true)
    }

    override fun onMenuAction(action: FabView.MenuAction) {
        when (action) {
            FabView.MenuAction.AddBubble -> spawnBubbleAtRandom()
            FabView.MenuAction.PopAll -> popAllBubbles()
            FabView.MenuAction.Settings -> openSettings()
            FabView.MenuAction.Stop -> {
                tearDown()
                stopSelf()
            }
        }
    }

    private fun popAllBubbles() {
        for (b in engine.bubbles) {
            if (!b.popping) {
                b.popping = true
                b.popProgress = 0f
                b.vx = 0f
                b.vy = 0f
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    // ----- Dismiss zone -----

    private fun attachDismissZone() {
        if (dismissView != null) return
        val view = DismissZoneView(this)
        val sizePx = dp(DismissZoneView.VIEW_SIZE_DP).toInt()
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        dismissCenterX = screenW * 0.5f
        dismissCenterY = screenH - dp(DismissZoneView.BOTTOM_OFFSET_DP)
        params.x = (dismissCenterX - sizePx / 2f).toInt()
        params.y = (dismissCenterY - sizePx / 2f).toInt()
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }
        dismissView = view
        dismissParams = params
    }

    private fun detachDismissZone() {
        val view = dismissView ?: return
        safeRemove(view)
        dismissView = null
        dismissParams = null
    }

    // ----- BubbleView.Callbacks -----

    override fun onBubbleDragStart(bubble: Bubble, rawX: Float, rawY: Float) {
        draggedBubble = bubble
        bubble.beingDragged = true
        // Lock the bubble's logical center to the touch point with offset, just
        // like the old canvas implementation.
        bubble.dragOffsetX = bubble.x - rawX
        bubble.dragOffsetY = bubble.y - rawY
        bubble.targetX = rawX + bubble.dragOffsetX
        bubble.targetY = rawY + bubble.dragOffsetY
        bubble.vx = 0f
        bubble.vy = 0f
        attachDismissZone()
        // Invalidate so the drag ring shows up.
        bubbleWindows[bubble.id]?.view?.invalidate()
    }

    override fun onBubbleDragMove(bubble: Bubble, rawX: Float, rawY: Float) {
        bubble.targetX = rawX + bubble.dragOffsetX
        bubble.targetY = rawY + bubble.dragOffsetY
    }

    override fun onBubbleDragEnd(bubble: Bubble, vxPxPerSec: Float, vyPxPerSec: Float, canceled: Boolean) {
        bubble.beingDragged = false
        draggedBubble = null
        detachDismissZone()
        bubbleWindows[bubble.id]?.view?.invalidate()

        // Dismiss check.
        val dist = hypot(bubble.x - dismissCenterX, bubble.y - dismissCenterY)
        if (!canceled && dist < dp(DismissZoneView.CAPTURE_RADIUS_DP)) {
            bubble.popping = true
            bubble.popProgress = 0f
            bubble.vx = 0f
            bubble.vy = 0f
            return
        }
        bubble.vx = vxPxPerSec
        bubble.vy = vyPxPerSec
    }

    // ----- Frame loop -----

    override fun doFrame(frameTimeNanos: Long) {
        if (!attached) return
        val last = lastFrameTimeNanos
        lastFrameTimeNanos = frameTimeNanos
        val dt = if (last == 0L) 1f / 60f
        else ((frameTimeNanos - last) / 1_000_000_000f).coerceAtMost(1f / 30f)

        // Step physics.
        engine.step(dt)

        // Dismiss-zone proximity.
        val held = draggedBubble
        val dismiss = dismissView
        if (dismiss != null) {
            val nearby = held != null &&
                hypot(held.x - dismissCenterX, held.y - dismissCenterY) <
                dp(DismissZoneView.PROXIMITY_RADIUS_DP)
            dismiss.setActive(nearby)
            dismiss.animate(dt)
        }

        // Move bubble windows + remove popped.
        val toRemove = ArrayList<Bubble>(2)
        for (b in engine.bubbles) {
            val win = bubbleWindows[b.id] ?: continue
            val slack = BubbleView.SLACK_PX
            val newX = (b.x - b.radius - slack).toInt()
            val newY = (b.y - b.radius - slack).toInt()
            // Only push an updateViewLayout if x/y actually changed (cheap guard).
            if (win.params.x != newX || win.params.y != newY) {
                win.params.x = newX
                win.params.y = newY
                try {
                    windowManager.updateViewLayout(win.view, win.params)
                } catch (_: Exception) {
                    // View not attached anymore — schedule removal.
                    toRemove.add(b)
                    continue
                }
            }
            // Visual state changes that require a redraw.
            if (b.popping || b.spawnProgress < 1f || b.beingDragged) {
                win.view.invalidate()
            }
        }

        // Remove fully-popped bubbles (engine has already removed them from its list).
        // Cross-check: any bubble window whose bubble is no longer in engine.bubbles
        // should be removed.
        if (bubbleWindows.size != engine.bubbles.size) {
            val liveIds = engine.bubbles.mapTo(HashSet()) { it.id }
            val orphanIds = bubbleWindows.keys.filterNot { it in liveIds }
            for (id in orphanIds) {
                val win = bubbleWindows.remove(id) ?: continue
                safeRemove(win.view)
            }
        }

        choreographer.postFrameCallback(this)
    }

    // ----- Sensor -----

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        gravityLP[0] = gravityAlpha * gravityLP[0] + (1 - gravityAlpha) * event.values[0]
        gravityLP[1] = gravityAlpha * gravityLP[1] + (1 - gravityAlpha) * event.values[1]
        gravityLP[2] = gravityAlpha * gravityLP[2] + (1 - gravityAlpha) * event.values[2]

        val rotation = currentRotation()
        val sx = gravityLP[0]
        val sy = gravityLP[1]
        val screenGx: Float
        val screenGy: Float
        when (rotation) {
            Surface.ROTATION_0 -> { screenGx = -sx; screenGy = sy }
            Surface.ROTATION_90 -> { screenGx = sy; screenGy = sx }
            Surface.ROTATION_180 -> { screenGx = sx; screenGy = -sy }
            Surface.ROTATION_270 -> { screenGx = -sy; screenGy = -sx }
            else -> { screenGx = -sx; screenGy = sy }
        }
        engine.gravityX = screenGx * pxPerMeter / 9.81f
        engine.gravityY = screenGy * pxPerMeter / 9.81f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                display?.rotation ?: Surface.ROTATION_0
            } catch (_: UnsupportedOperationException) {
                windowManager.defaultDisplay.rotation
            }
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    // ----- Notification -----

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, BubbleOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)
            .addAction(0, "Open", openPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    // ----- Utility -----

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
