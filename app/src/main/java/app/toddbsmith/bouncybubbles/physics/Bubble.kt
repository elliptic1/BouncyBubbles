package app.toddbsmith.bouncybubbles.physics

/**
 * A single bubble in the simulation.
 *
 * Positions are in screen pixels. Velocities are pixels-per-second.
 * Colors are 32-bit ARGB ints for the radial gradient endpoints.
 */
class Bubble(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    val colorTop: Int,
    val colorBottom: Int,
) {
    // Drag state. Mutated by the touch handler in BubbleCanvasView.
    var beingDragged: Boolean = false
    var dragOffsetX: Float = 0f
    var dragOffsetY: Float = 0f

    // Target position while being dragged (where the finger is, plus offset).
    // The actual x/y springs toward this each frame so motion feels juicy.
    var targetX: Float = 0f
    var targetY: Float = 0f

    // Pop animation state. While popping, scale shrinks from 1.0 -> 0.0,
    // then PhysicsEngine removes the bubble. Not driven by physics.
    var popping: Boolean = false
    var popProgress: Float = 0f // 0 = just started, 1 = fully popped

    // Spawn-in scale animation: 0 -> 1 over ~180ms. Lets newly-added bubbles
    // animate in (FAB "Add bubble" action). Idle bubbles sit at 1.0.
    var spawnProgress: Float = 1f

    /** Effective visual scale combining pop (shrinking) and spawn (growing). */
    val scale: Float
        get() {
            val popScale = if (popping) (1f - popProgress).coerceAtLeast(0f) else 1f
            val spawnScale = spawnProgress.coerceIn(0f, 1f)
            // Tiny overshoot on spawn-in for juice.
            val eased = if (spawnScale < 1f) {
                // ease-out-back-ish: 1.1 peak at p~=0.7, settles to 1.0.
                val p = spawnScale
                val s = 1.4f
                ((1f + s) * p * p * p) - (s * p * p)
            } else 1f
            return popScale * eased.coerceIn(0f, 1.15f)
        }

    /** Stable id for window-bookkeeping in the service. */
    val id: Int = nextId.getAndIncrement()

    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicInteger(1)
    }
}
