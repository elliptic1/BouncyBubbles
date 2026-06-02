package app.toddbsmith.bouncybubbles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings the launcher passes through to the overlay service.
 */
data class LauncherSettings(
    val initialCount: Int,
    val radiusMinDp: Float,
    val radiusMaxDp: Float,
    val gravityScale: Float,
    val bounciness: Float,
)

enum class BubbleSize(
    val label: String,
    val rMinDp: Float,
    val rMaxDp: Float,
) {
    Small("Small", 22f, 36f),
    Medium("Medium", 36f, 60f),
    Large("Large", 56f, 80f),
}

@Composable
fun LauncherScreen(
    overlayGranted: Boolean,
    running: Boolean,
    onRequestOverlay: () -> Unit,
    onStart: (LauncherSettings) -> Unit,
    onStop: () -> Unit,
    onPlayAirHockey: () -> Unit = {},
) {
    // Dark, playful theme. Material 3 dark palette as a baseline.
    val scheme = darkColorScheme(
        primary = Color(0xFFFF6B9D),
        onPrimary = Color.White,
        secondary = Color(0xFF4ECDC4),
        background = Color(0xFF12131F),
        surface = Color(0xFF1A1B2E),
        onSurface = Color(0xFFE8E8F0),
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()

                PermissionStatusCard(
                    granted = overlayGranted,
                    onRequestOverlay = onRequestOverlay,
                )

                var count by remember { mutableIntStateOf(6) }
                var size by remember { mutableStateOf(BubbleSize.Medium) }
                var gravity by remember { mutableFloatStateOf(1.0f) }
                var bounce by remember { mutableFloatStateOf(0.85f) }

                SettingsCard(
                    count = count,
                    size = size,
                    gravity = gravity,
                    bounce = bounce,
                    onCount = { count = it },
                    onSize = { size = it },
                    onGravity = { gravity = it },
                    onBounce = { bounce = it },
                )

                Spacer(modifier = Modifier.height(4.dp))

                BigActionButton(
                    running = running,
                    enabled = overlayGranted,
                    onStart = {
                        onStart(
                            LauncherSettings(
                                initialCount = count,
                                radiusMinDp = size.rMinDp,
                                radiusMaxDp = size.rMaxDp,
                                gravityScale = gravity,
                                bounciness = bounce,
                            ),
                        )
                    },
                    onStop = onStop,
                )

                AirHockeyButton(onClick = onPlayAirHockey)
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Three little bubble icons as a logomark.
            BubbleDot(28.dp, Color(0xFFFF6B9D), Color(0xFFE91E63))
            Spacer(modifier = Modifier.size(6.dp))
            BubbleDot(22.dp, Color(0xFFFFE082), Color(0xFFFB8C00))
            Spacer(modifier = Modifier.size(6.dp))
            BubbleDot(18.dp, Color(0xFF80DEEA), Color(0xFF00838F))
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                "BouncyBubbles",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Glossy bubbles that float, roll, and pop — on top of everything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun BubbleDot(size: androidx.compose.ui.unit.Dp, top: Color, bottom: Color) {
    // Cheap radial-gradient illusion via a clipped circle with a brush.
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(top, bottom),
                ),
            ),
    )
}

@Composable
private fun PermissionStatusCard(
    granted: Boolean,
    onRequestOverlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (granted) "Overlay permission granted" else "Overlay permission required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!granted) {
                Text(
                    "BouncyBubbles needs permission to draw over other apps so the bubbles can float on top of everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Button(onClick = onRequestOverlay) { Text("Grant overlay permission") }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    count: Int,
    size: BubbleSize,
    gravity: Float,
    bounce: Float,
    onCount: (Int) -> Unit,
    onSize: (BubbleSize) -> Unit,
    onGravity: (Float) -> Unit,
    onBounce: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            LabeledSlider(
                label = "Initial bubble count: $count",
                value = count.toFloat(),
                onValueChange = { onCount(it.toInt().coerceIn(1, 20)) },
                valueRange = 1f..20f,
                steps = 18,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Bubble size", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BubbleSize.entries.forEachIndexed { index, opt ->
                        SegmentedButton(
                            selected = size == opt,
                            onClick = { onSize(opt) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = BubbleSize.entries.size,
                            ),
                        ) { Text(opt.label) }
                    }
                }
            }

            LabeledSlider(
                label = "Gravity strength: ${"%.2f".format(gravity)}x",
                value = gravity,
                onValueChange = onGravity,
                valueRange = 0f..2f,
            )

            LabeledSlider(
                label = "Bounciness: ${"%.2f".format(bounce)}",
                value = bounce,
                onValueChange = onBounce,
                valueRange = 0.5f..0.95f,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun AirHockeyButton(onClick: () -> Unit) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
        ),
    ) {
        Text(
            "🏒  Play Air Hockey",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BigActionButton(
    running: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        enabled = enabled || running,
        onClick = { if (running) onStop() else onStart() },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Text(
            if (running) "Stop Bubbles" else "Launch Bubbles",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
