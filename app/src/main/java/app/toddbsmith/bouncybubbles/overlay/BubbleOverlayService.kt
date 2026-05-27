package app.toddbsmith.bouncybubbles.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.toddbsmith.bouncybubbles.MainActivity
import app.toddbsmith.bouncybubbles.R

/**
 * Foreground service that owns the WindowManager overlay window and a
 * BubbleCanvasView. Reads tuning extras off the start Intent.
 */
class BubbleOverlayService : Service() {

    companion object {
        const val EXTRA_INITIAL_COUNT = "extra_initial_count"
        const val EXTRA_RADIUS_MIN_DP = "extra_radius_min_dp"
        const val EXTRA_RADIUS_MAX_DP = "extra_radius_max_dp"
        const val EXTRA_GRAVITY_SCALE = "extra_gravity_scale"
        const val EXTRA_BOUNCINESS = "extra_bounciness"

        const val ACTION_STOP = "app.toddbsmith.bouncybubbles.STOP"

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "bouncy_bubbles_overlay"

        /**
         * Simple running flag. The launcher polls this so its button label
         * reflects current state. A real app might bind to the service or
         * use a LocalBroadcastManager / Flow.
         */
        @Volatile
        var running: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var canvasView: BubbleCanvasView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be able to draw overlays.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (canvasView == null) {
            addOverlay(intent)
        }

        running = true
        return START_NOT_STICKY
    }

    private fun addOverlay(intent: Intent?) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val initialCount = intent?.getIntExtra(EXTRA_INITIAL_COUNT, 6) ?: 6
        val rMin = intent?.getFloatExtra(EXTRA_RADIUS_MIN_DP, 36f) ?: 36f
        val rMax = intent?.getFloatExtra(EXTRA_RADIUS_MAX_DP, 60f) ?: 60f
        val gravityScale = intent?.getFloatExtra(EXTRA_GRAVITY_SCALE, 1.0f) ?: 1.0f
        val bounciness = intent?.getFloatExtra(EXTRA_BOUNCINESS, 0.85f) ?: 0.85f

        val view = BubbleCanvasView(
            context = this,
            initialCount = initialCount,
            radiusRangeDp = rMin..rMax,
            gravityScale = gravityScale,
            bounciness = bounciness,
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START

        wm.addView(view, params)
        canvasView = view
    }

    private fun stopOverlay() {
        canvasView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: IllegalArgumentException) {
                // Already removed; ignore.
            }
        }
        canvasView = null
        windowManager = null
        running = false
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    // ---------------- Notification ----------------

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
            .setContentIntent(stopPending)
            .addAction(0, "Open", openPending)
            .addAction(0, "Stop", stopPending)
            .build()
    }
}
