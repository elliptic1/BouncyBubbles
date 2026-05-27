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
}
