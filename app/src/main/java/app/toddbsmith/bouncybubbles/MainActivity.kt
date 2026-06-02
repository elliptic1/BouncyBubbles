package app.toddbsmith.bouncybubbles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.toddbsmith.bouncybubbles.game.AirHockeyActivity
import app.toddbsmith.bouncybubbles.overlay.BubbleOverlayService
import app.toddbsmith.bouncybubbles.ui.LauncherScreen
import app.toddbsmith.bouncybubbles.ui.LauncherSettings
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notifPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* result ignored — the user can decline; the FGS still runs. */ }

        setContent {
            // Compose state. Re-checked each time the launcher screen recomposes
            // (re-enters foreground).
            var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
            var running by remember { mutableStateOf(BubbleOverlayService.running) }

            // Tick to refresh state — covers returning from system settings and
            // the service starting/stopping itself.
            LaunchedEffect(Unit) {
                while (true) {
                    overlayGranted = Settings.canDrawOverlays(this@MainActivity)
                    running = BubbleOverlayService.running
                    delay(400)
                }
            }

            LauncherScreen(
                overlayGranted = overlayGranted,
                running = running,
                onRequestOverlay = { openOverlaySettings() },
                onStart = { settings ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    startBubbleService(settings)
                },
                onStop = { stopBubbleService() },
                onPlayAirHockey = {
                    startActivity(Intent(this, AirHockeyActivity::class.java))
                },
            )
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startBubbleService(s: LauncherSettings) {
        val intent = Intent(this, BubbleOverlayService::class.java).apply {
            putExtra(BubbleOverlayService.EXTRA_INITIAL_COUNT, s.initialCount)
            putExtra(BubbleOverlayService.EXTRA_RADIUS_MIN_DP, s.radiusMinDp)
            putExtra(BubbleOverlayService.EXTRA_RADIUS_MAX_DP, s.radiusMaxDp)
            putExtra(BubbleOverlayService.EXTRA_GRAVITY_SCALE, s.gravityScale)
            putExtra(BubbleOverlayService.EXTRA_BOUNCINESS, s.bounciness)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBubbleService() {
        val intent = Intent(this, BubbleOverlayService::class.java).apply {
            action = BubbleOverlayService.ACTION_STOP
        }
        startService(intent)
    }
}
