package app.toddbsmith.bouncybubbles.game

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
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.toddbsmith.bouncybubbles.MainActivity
import app.toddbsmith.bouncybubbles.R

/**
 * Runs air hockey in a system overlay window so it floats over the home screen
 * and other apps — exactly like the bubble overlay. Mirrors BubbleOverlayService's
 * proven setup: TYPE_APPLICATION_OVERLAY window + foreground notification.
 *
 * While playing, the window grabs touches (you're flinging mallets), but the table
 * is translucent so whatever's behind shows through. Stop from the notification.
 */
class AirHockeyOverlayService : Service() {

    companion object {
        const val EXTRA_VS_AI = "extra_vs_ai"
        const val ACTION_STOP = "app.toddbsmith.bouncybubbles.STOP_AIRHOCKEY"
        private const val NOTIFICATION_ID = 4343
        private const val CHANNEL_ID = "air_hockey_overlay"
    }

    private lateinit var windowManager: WindowManager
    private var view: AirHockeyView? = null
    private var attached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tearDown(); stopSelf(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission needed", Toast.LENGTH_SHORT).show()
            stopSelf(); return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!attached) addGameWindow(intent?.getBooleanExtra(EXTRA_VS_AI, true) ?: true)
        return START_NOT_STICKY
    }

    override fun onDestroy() { tearDown(); super.onDestroy() }

    private fun addGameWindow(vsAi: Boolean) {
        val game = AirHockeyView(
            this,
            vsAi = vsAi,
            onScore = { _, _ -> },
            onWin = { stopSelf() },          // game over → close the overlay
            transparent = true,
        )
        // FLAG_NOT_FOCUSABLE so we don't steal the keyboard/IME, but NO NOT_TOUCHABLE
        // because the game needs touch. Fills the screen incl. under the status bar.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(game, params)
            view = game
            attached = true
            game.start()
            Toast.makeText(this, "Air hockey — swipe the notification's Stop to exit", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start overlay: ${e.message}", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun tearDown() {
        attached = false
        view?.let {
            it.stop()
            try { windowManager.removeView(it) } catch (_: Exception) { /* already detached */ }
        }
        view = null
    }

    // ----- Notification (mirrors BubbleOverlayService) -----

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Air Hockey overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, AirHockeyOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPending = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Air hockey is floating on screen")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPending)
            .setContentIntent(openPending)
            .build()
    }
}
