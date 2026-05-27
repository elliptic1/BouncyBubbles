package app.toddbsmith.bouncybubbles.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsEngineTest {

    /**
     * Two equal-mass bubbles moving directly toward each other along the X axis
     * should swap velocities under a perfectly elastic collision (restitution=1).
     */
    @Test
    fun elasticCollision_equalMass_swapsVelocities() {
        val engine = PhysicsEngine().apply { restitution = 1.0f }

        val a = Bubble(
            x = 0f, y = 0f,
            vx = 100f, vy = 0f,
            radius = 10f,
            colorTop = 0, colorBottom = 0,
        )
        val b = Bubble(
            x = 18f, y = 0f, // overlap of 2 px so resolvePair triggers
            vx = -100f, vy = 0f,
            radius = 10f,
            colorTop = 0, colorBottom = 0,
        )

        engine.resolvePair(a, b)

        // Equal mass elastic head-on: velocities swap.
        assertEquals(-100f, a.vx, 0.01f)
        assertEquals(100f, b.vx, 0.01f)
        // Y velocities untouched.
        assertEquals(0f, a.vy, 0.01f)
        assertEquals(0f, b.vy, 0.01f)
        // And they should no longer overlap.
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue("bubbles should be separated, was $dist", dist >= a.radius + b.radius - 0.01f)
    }

    /**
     * Two bubbles moving apart already shouldn't be perturbed (no impulse applied).
     */
    @Test
    fun elasticCollision_separating_isNoOpForVelocity() {
        val engine = PhysicsEngine().apply { restitution = 0.85f }

        val a = Bubble(0f, 0f, -50f, 0f, 10f, 0, 0)
        val b = Bubble(18f, 0f, 50f, 0f, 10f, 0, 0)
        val initialAvx = a.vx
        val initialBvx = b.vx

        engine.resolvePair(a, b)

        // No impulse, since they're already separating.
        assertEquals(initialAvx, a.vx, 0.01f)
        assertEquals(initialBvx, b.vx, 0.01f)
    }

    /**
     * Larger bubble (more mass) takes less of the positional correction.
     */
    @Test
    fun positionalCorrection_isMassWeighted() {
        val engine = PhysicsEngine()

        val small = Bubble(0f, 0f, 0f, 0f, 10f, 0, 0)
        val large = Bubble(15f, 0f, 0f, 0f, 30f, 0, 0)

        val smallX0 = small.x
        val largeX0 = large.x

        engine.resolvePair(small, large)

        val smallMoved = kotlin.math.abs(small.x - smallX0)
        val largeMoved = kotlin.math.abs(large.x - largeX0)
        // Small bubble should be pushed more than the large one.
        assertTrue(
            "small ($smallMoved) should move more than large ($largeMoved)",
            smallMoved > largeMoved,
        )
    }
}
