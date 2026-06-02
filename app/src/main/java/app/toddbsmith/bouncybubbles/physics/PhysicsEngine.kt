package app.toddbsmith.bouncybubbles.physics

import kotlin.math.sqrt

/**
 * Hand-rolled 2D physics for the bubble simulation.
 *
 * - Semi-implicit Euler integration with sub-stepping (default 4 substeps).
 * - Gravity is applied as an acceleration vector that the overlay sets each
 *   frame from the (orientation-corrected) accelerometer reading.
 * - Edge collisions clamp position and reflect velocity with restitution.
 * - Bubble-bubble collisions: O(n^2) overlap check, positional correction,
 *   then equal-restitution impulse along the collision normal.
 *
 * Mass is treated as radius^2 (proportional to area, since bubbles are 2D).
 *
 * The engine doesn't know anything about touch or rendering — the canvas
 * view drives it by setting [gravityX]/[gravityY], calling [step], and then
 * reading [bubbles] for drawing.
 */
/**
 * A static circular obstacle that bubbles bounce off of (e.g. the FAB).
 * The obstacle never moves under physics — the service mutates [x],[y]
 * directly when the FAB is repositioned, and the engine treats it as
 * infinite mass.
 */
class StaticObstacle(
    var x: Float,
    var y: Float,
    var radius: Float,
)

class PhysicsEngine {

    val bubbles: MutableList<Bubble> = mutableListOf()
    val obstacles: MutableList<StaticObstacle> = mutableListOf()

    // Gravity in screen-space px/s^2. Positive Y points DOWN the screen.
    var gravityX: Float = 0f
    var gravityY: Float = 0f

    // Bounds — set by the canvas view on size change.
    var width: Float = 0f
    var height: Float = 0f

    // System-bar insets (status bar, navigation bar) in raw screen px. Bubbles are
    // clamped to the tappable safe area [inset..size-inset] so they never settle
    // behind the nav bar / status bar, where touches go to the system, not the bubble.
    var insetTop: Float = 0f
    var insetBottom: Float = 0f
    var insetLeft: Float = 0f
    var insetRight: Float = 0f

    // Tunable knobs.
    var restitution: Float = 0.85f          // bounciness (0..1)
    var linearDampingPerSec: Float = 0.25f  // fraction of speed lost per second
    var substeps: Int = 4
    var popDurationSec: Float = 0.18f

    fun addBubble(b: Bubble) {
        bubbles.add(b)
    }

    fun removeBubble(b: Bubble) {
        bubbles.remove(b)
    }

    /**
     * Advance the simulation by [dt] seconds total, split into [substeps]
     * smaller steps. Sub-stepping keeps fast-moving bubbles from tunneling.
     */
    fun step(dt: Float) {
        if (dt <= 0f) return
        val sub = substeps.coerceAtLeast(1)
        val h = dt / sub

        // Cache damping factor per sub-step. We approximate exponential decay
        // with a linear factor here — at 60Hz/4 substeps it's indistinguishable.
        val dampSub = (1f - linearDampingPerSec * h).coerceIn(0f, 1f)

        for (s in 0 until sub) {
            integrate(h, dampSub)
            resolveEdges()
            resolveObstacleCollisions()
            resolveBubbleCollisions()
        }

        advancePops(dt)
        advanceSpawns(dt)
    }

    private fun advanceSpawns(dt: Float) {
        // ~180ms spawn-in animation.
        val rate = dt / 0.18f
        var i = 0
        val n = bubbles.size
        while (i < n) {
            val b = bubbles[i]
            if (b.spawnProgress < 1f) {
                b.spawnProgress = (b.spawnProgress + rate).coerceAtMost(1f)
            }
            i++
        }
    }

    private fun integrate(h: Float, damp: Float) {
        // Indexed for loop, no allocation in the hot path.
        var i = 0
        val n = bubbles.size
        while (i < n) {
            val b = bubbles[i]
            if (b.popping) { i++; continue }

            if (b.beingDragged) {
                // 1:1 finger tracking — bubble teleports to the target every
                // substep. No spring, no lag. On release the service applies
                // the touch-sample fling velocity directly to vx/vy.
                b.x = b.targetX
                b.y = b.targetY
                b.vx = 0f
                b.vy = 0f
            } else {
                b.vx += gravityX * h
                b.vy += gravityY * h
                b.vx *= damp
                b.vy *= damp
                b.x += b.vx * h
                b.y += b.vy * h
            }
            i++
        }
    }

    private fun resolveEdges() {
        if (width <= 0f || height <= 0f) return

        // Tappable safe area: clamp inside the system-bar insets so bubbles stay
        // off the nav bar / status bar and remain tappable.
        val left = insetLeft
        val top = insetTop
        val right = width - insetRight
        val bottom = height - insetBottom

        var i = 0
        val n = bubbles.size
        while (i < n) {
            val b = bubbles[i]
            if (b.popping) { i++; continue }
            val r = b.radius

            if (b.x - r < left) {
                b.x = left + r
                if (b.vx < 0f) b.vx = -b.vx * restitution
            } else if (b.x + r > right) {
                b.x = right - r
                if (b.vx > 0f) b.vx = -b.vx * restitution
            }

            if (b.y - r < top) {
                b.y = top + r
                if (b.vy < 0f) b.vy = -b.vy * restitution
            } else if (b.y + r > bottom) {
                b.y = bottom - r
                if (b.vy > 0f) b.vy = -b.vy * restitution
            }
            i++
        }
    }

    /**
     * Static obstacles are infinite-mass circles. Bubbles separate outward
     * along the (bubble - obstacle) normal and reflect velocity along that
     * normal with [restitution]. Obstacles do not accumulate velocity.
     *
     * A bubble that's currently being dragged still gets positionally
     * separated so it can't be jammed through the FAB by the user's finger,
     * but its velocity isn't changed (drag spring owns it).
     */
    private fun resolveObstacleCollisions() {
        val obs = obstacles
        val on = obs.size
        if (on == 0) return
        var i = 0
        val bn = bubbles.size
        while (i < bn) {
            val b = bubbles[i]
            if (b.popping) { i++; continue }
            var k = 0
            while (k < on) {
                val o = obs[k]
                val dx = b.x - o.x
                val dy = b.y - o.y
                val rSum = b.radius + o.radius
                val distSq = dx * dx + dy * dy
                if (distSq < rSum * rSum) {
                    val dist = if (distSq > 1e-6f) sqrt(distSq) else 0f
                    val nx: Float
                    val ny: Float
                    if (dist > 1e-4f) {
                        nx = dx / dist
                        ny = dy / dist
                    } else {
                        // Coincident — push straight up by default.
                        nx = 0f
                        ny = -1f
                    }
                    val overlap = rSum - dist
                    // Push bubble fully out (obstacle is infinite mass).
                    b.x += nx * overlap
                    b.y += ny * overlap
                    if (!b.beingDragged) {
                        val vDotN = b.vx * nx + b.vy * ny
                        if (vDotN < 0f) {
                            // Reflect velocity along the normal.
                            b.vx -= (1f + restitution) * vDotN * nx
                            b.vy -= (1f + restitution) * vDotN * ny
                        }
                    }
                }
                k++
            }
            i++
        }
    }

    private fun resolveBubbleCollisions() {
        val n = bubbles.size
        var i = 0
        while (i < n) {
            val a = bubbles[i]
            if (a.popping) { i++; continue }
            var j = i + 1
            while (j < n) {
                val b = bubbles[j]
                if (!b.popping) {
                    resolvePair(a, b)
                }
                j++
            }
            i++
        }
    }

    /**
     * Resolve one pair: separate overlap along the collision normal, then
     * apply an equal-restitution impulse. Skips when the pair is moving apart.
     *
     * Internal so the unit test can hit it directly.
     */
    internal fun resolvePair(a: Bubble, b: Bubble) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val rSum = a.radius + b.radius
        val distSq = dx * dx + dy * dy
        if (distSq >= rSum * rSum) return
        if (distSq < 1e-6f) {
            // Coincident — nudge them apart deterministically.
            b.x += rSum * 0.5f
            return
        }
        val dist = sqrt(distSq)
        val nx = dx / dist
        val ny = dy / dist
        val overlap = rSum - dist

        // Mass proportional to area (radius^2).
        val ma = a.radius * a.radius
        val mb = b.radius * b.radius
        val totalMass = ma + mb

        // Positional correction split by inverse mass — heavier moves less.
        val aShare = mb / totalMass
        val bShare = ma / totalMass
        if (!a.beingDragged) {
            a.x -= nx * overlap * aShare
            a.y -= ny * overlap * aShare
        }
        if (!b.beingDragged) {
            b.x += nx * overlap * bShare
            b.y += ny * overlap * bShare
        }

        // Relative velocity along the normal.
        val rvx = b.vx - a.vx
        val rvy = b.vy - a.vy
        val velAlongNormal = rvx * nx + rvy * ny
        if (velAlongNormal > 0f) return // already separating

        val e = restitution
        // Impulse scalar j for equal-restitution 2D collision.
        val jImpulse = -(1f + e) * velAlongNormal / (1f / ma + 1f / mb)

        val ix = jImpulse * nx
        val iy = jImpulse * ny

        if (!a.beingDragged) {
            a.vx -= ix / ma
            a.vy -= iy / ma
        }
        if (!b.beingDragged) {
            b.vx += ix / mb
            b.vy += iy / mb
        }
    }

    private fun advancePops(dt: Float) {
        if (popDurationSec <= 0f) return
        var i = 0
        while (i < bubbles.size) {
            val b = bubbles[i]
            if (b.popping) {
                b.popProgress += dt / popDurationSec
                if (b.popProgress >= 1f) {
                    bubbles.removeAt(i)
                    continue
                }
            }
            i++
        }
    }
}
