package app.toddbsmith.bouncybubbles.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Self-contained 2-player / vs-AI air hockey on a portrait table.
 *
 * Why not reuse PhysicsEngine? That engine's obstacles only *reflect* velocity —
 * a moving mallet there can't add energy to the puck, so you could never take a
 * shot. Air hockey needs mallets that transfer their own momentum into the puck,
 * goal gaps in the end walls, and scoring. So this is a small dedicated game loop.
 *
 * Coordinates are raw view pixels. The table is the full view inset by [wall].
 * Player 1 (you) defends the BOTTOM goal; Player 2 / AI defends the TOP goal.
 */
class AirHockeyView(
    context: Context,
    private val vsAi: Boolean,
    private val onScore: (p1: Int, p2: Int) -> Unit,
    private val onWin: (p1Won: Boolean) -> Unit,
    /** When true the table is see-through (for floating over the home screen). */
    private val transparent: Boolean = false,
) : View(context), Choreographer.FrameCallback {

    // ── Tunables ────────────────────────────────────────────────────────────
    private val wall = dp(10f)              // table border thickness
    private val puckR = dp(26f)
    private val malletR = dp(40f)
    private val goalFrac = 0.42f            // goal width as fraction of table width
    private val restitution = 0.92f         // wall bounciness
    private val puckDamping = 0.20f         // fraction of speed lost per second (air friction)
    private val maxPuckSpeed = dp(4200f)    // px/s clamp to prevent tunneling
    private val substeps = 6
    private val winScore = 7
    private val aiSpeed = dp(1500f)         // max AI mallet speed px/s

    // ── State ───────────────────────────────────────────────────────────────
    private var puckX = 0f; private var puckY = 0f
    private var puckVx = 0f; private var puckVy = 0f

    private val p1 = Mallet()   // bottom (human)
    private val p2 = Mallet()   // top (human or AI)

    private var score1 = 0; private var score2 = 0
    private var lastFrameNanos = 0L
    private var running = false
    private var goalFlashSec = 0f           // brief freeze + flash after a goal
    private var stuckSec = 0f               // how long the puck has lingered in one area
    private var anchorX = 0f; private var anchorY = 0f  // reference point for stuck detection

    // Pointer → which mallet it's dragging (-1 none)
    private var p1Pointer = -1
    private var p2Pointer = -1

    // ── Paints ──────────────────────────────────────────────────────────────
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E2A3A") }
    private val feltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#103D54") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3FA9C9"); style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A1820"); style = Paint.Style.FILL
    }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
    private val puckRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444"); style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5Fd0ef"); textAlign = Paint.Align.CENTER; textSize = dp(56f); alpha = 60
    }
    private var flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private class Mallet {
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f      // velocity inferred from finger movement
        var topColor = Color.WHITE; var botColor = Color.GRAY
    }

    init {
        keepScreenOn = true
        p1.topColor = Color.parseColor("#FF6B9D"); p1.botColor = Color.parseColor("#C2185B")
        p2.topColor = Color.parseColor("#4ECDC4"); p2.botColor = Color.parseColor("#00796B")
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────
    private val left get() = wall
    private val right get() = width - wall
    private val top get() = wall
    private val bottom get() = height - wall
    private val midY get() = height / 2f
    private val goalHalf get() = (right - left) * goalFrac / 2f
    private val centerX get() = width / 2f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        resetPositions(servingToP1 = true)
        score1 = 0; score2 = 0; onScore(0, 0)
    }

    private fun resetPositions(servingToP1: Boolean) {
        puckX = centerX
        puckY = midY
        puckVx = 0f; puckVy = 0f
        p1.x = centerX; p1.y = bottom - (bottom - midY) / 2f
        p2.x = centerX; p2.y = top + (midY - top) / 2f
        p1.vx = 0f; p1.vy = 0f; p2.vx = 0f; p2.vy = 0f
        // Tiny nudge toward the receiver so the puck isn't dead-still.
        puckVy = if (servingToP1) dp(300f) else -dp(300f)
        // Reset stuck-detection anchor to the new puck position.
        anchorX = puckX; anchorY = puckY; stuckSec = 0f
    }

    fun start() { if (!running) { running = true; lastFrameNanos = 0L; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this); keepScreenOn = false }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
        var dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameTimeNanos
        if (dt > 1f / 30f) dt = 1f / 30f      // clamp big stalls

        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun update(dt: Float) {
        // The frame loop can fire before the view is measured (width/height == 0),
        // which makes the mallet-constraint ranges empty. Wait for a real layout.
        if (width <= 0 || height <= 0 || midY - malletR <= top + malletR) return

        if (goalFlashSec > 0f) {
            goalFlashSec = max(0f, goalFlashSec - dt)
            return                              // freeze briefly after a goal
        }
        if (vsAi) updateAi(dt)

        val h = dt / substeps
        val damp = (1f - puckDamping * h).coerceIn(0f, 1f)
        repeat(substeps) {
            // Integrate puck
            puckVx *= damp; puckVy *= damp
            puckX += puckVx * h
            puckY += puckVy * h
            clampPuckSpeed()
            collideWalls()
            collideMallet(p1)
            collideMallet(p2)
        }
        unstickPuck(dt)
        checkGoals()
    }

    /**
     * Anti-stall: a puck can lose enough energy (damping + restitution) to settle
     * in a corner, where the AI then parks its mallet against it and it never
     * comes free. If the puck stays slow for too long, nudge it toward center —
     * and harder if it's pinned near a wall.
     */
    private fun unstickPuck(dt: Float) {
        // Position-based detection: if the puck stays within a small radius of an
        // anchor point for too long, it's trapped — regardless of momentary velocity
        // spikes from the AI mallet jittering against it (that velocity-based check
        // was the reason it still got stuck). Track displacement from the anchor.
        val roam = hypot(puckX - anchorX, puckY - anchorY)
        if (roam > dp(90f)) {
            // Puck has genuinely travelled — reset the anchor and the timer.
            anchorX = puckX; anchorY = puckY
            stuckSec = 0f
        } else {
            // Lingering near the anchor. Corners are the real trap, so count faster there.
            val nearEdge = puckX < left + puckR * 2.5f || puckX > right - puckR * 2.5f ||
                puckY < top + puckR * 2.5f || puckY > bottom - puckR * 2.5f
            stuckSec += dt * (if (nearEdge) 2.0f else 1.0f)
        }

        if (stuckSec > 1.5f) {
            // Free it: pull off the wall, then kick toward center hard enough to clear.
            puckX = puckX.coerceIn(left + puckR + dp(4f), right - puckR - dp(4f))
            puckY = puckY.coerceIn(top + puckR + dp(4f), bottom - puckR - dp(4f))
            val tx = centerX - puckX
            val ty = midY - puckY
            val d = hypot(tx, ty).coerceAtLeast(1f)
            val kick = dp(1600f)
            puckVx = tx / d * kick
            puckVy = ty / d * kick
            anchorX = puckX; anchorY = puckY
            stuckSec = 0f
        }
    }

    private fun clampPuckSpeed() {
        val sp = hypot(puckVx, puckVy)
        if (sp > maxPuckSpeed) { val k = maxPuckSpeed / sp; puckVx *= k; puckVy *= k }
    }

    private fun collideWalls() {
        // Left / right always solid.
        if (puckX - puckR < left) { puckX = left + puckR; if (puckVx < 0) puckVx = -puckVx * restitution }
        if (puckX + puckR > right) { puckX = right - puckR; if (puckVx > 0) puckVx = -puckVx * restitution }

        val inGoalColumn = abs(puckX - centerX) < goalHalf
        // Top wall: solid except goal gap.
        if (puckY - puckR < top && !inGoalColumn) { puckY = top + puckR; if (puckVy < 0) puckVy = -puckVy * restitution }
        // Bottom wall: solid except goal gap.
        if (puckY + puckR > bottom && !inGoalColumn) { puckY = bottom - puckR; if (puckVy > 0) puckVy = -puckVy * restitution }
    }

    /** Mallet acts as an infinite-mass moving body: puck takes on its momentum along the normal. */
    private fun collideMallet(m: Mallet) {
        val dx = puckX - m.x
        val dy = puckY - m.y
        val rSum = puckR + malletR
        val distSq = dx * dx + dy * dy
        if (distSq >= rSum * rSum) return
        val dist = if (distSq > 1e-3f) hypot(dx, dy) else 0.001f
        val nx = if (dist > 1e-3f) dx / dist else 0f
        val ny = if (dist > 1e-3f) dy / dist else -1f
        // Separate puck fully out of the mallet.
        val overlap = rSum - dist
        puckX += nx * overlap
        puckY += ny * overlap
        // Relative velocity along normal (mallet treated as infinite mass).
        val rvx = puckVx - m.vx
        val rvy = puckVy - m.vy
        val vAlongN = rvx * nx + rvy * ny
        if (vAlongN < 0f) {
            puckVx -= (1f + restitution) * vAlongN * nx
            puckVy -= (1f + restitution) * vAlongN * ny
        } else {
            // Even a glancing/stationary contact should push the puck off the mallet face
            // so a moving mallet always "hits" it.
            puckVx += m.vx * 0.5f
            puckVy += m.vy * 0.5f
        }
        clampPuckSpeed()
    }

    private fun checkGoals() {
        // Puck fully past the top wall within the gap → P1 scores.
        if (puckY + puckR < top) {
            score1++; onScore(score1, score2); afterGoal(p1Won = true); return
        }
        // Puck fully past the bottom wall within the gap → P2 scores.
        if (puckY - puckR > bottom) {
            score2++; onScore(score1, score2); afterGoal(p1Won = false); return
        }
    }

    private fun afterGoal(p1Won: Boolean) {
        goalFlashSec = 0.7f
        if (score1 >= winScore || score2 >= winScore) {
            running = false
            onWin(score1 >= winScore)
            return
        }
        // Loser of the point serves.
        resetPositions(servingToP1 = !p1Won)
    }

    // ── AI (controls P2, the top mallet) ──────────────────────────────────────
    private fun updateAi(dt: Float) {
        val defendY = top + (midY - top) * 0.30f
        val targetX: Float
        val targetY: Float
        // If the puck is on the AI's half and moving toward its goal, attack it.
        if (puckY < midY) {
            // If the puck is hugging a side wall, attack from the inside edge so we
            // shove it back toward center instead of pinning it against the wall.
            val nearLeft = puckX < left + malletR + puckR
            val nearRight = puckX > right - malletR - puckR
            targetX = when {
                nearLeft -> puckX + malletR * 0.8f
                nearRight -> puckX - malletR * 0.8f
                else -> puckX
            }
            targetY = min(puckY - puckR, midY - malletR)   // get behind/above the puck to strike down
        } else {
            // Puck on player's half — recenter to defend the goal mouth.
            targetX = centerX + (puckX - centerX) * 0.4f
            targetY = defendY
        }
        val dx = targetX - p2.x
        val dy = targetY - p2.y
        val d = hypot(dx, dy)
        val step = aiSpeed * dt
        val nx = if (d > 1e-3f) dx / d else 0f
        val ny = if (d > 1e-3f) dy / d else 0f
        val move = min(step, d)
        val newX = p2.x + nx * move
        val newY = p2.y + ny * move
        p2.vx = (newX - p2.x) / dt
        p2.vy = (newY - p2.y) / dt
        p2.x = newX; p2.y = newY
        constrainMallet(p2, topHalf = true)
    }

    private fun constrainMallet(m: Mallet, topHalf: Boolean) {
        m.x = clampSafe(m.x, left + malletR, right - malletR)
        if (topHalf) m.y = clampSafe(m.y, top + malletR, midY - malletR)
        else m.y = clampSafe(m.y, midY + malletR, bottom - malletR)
    }

    /** coerceIn that tolerates an inverted/empty range (returns the midpoint). */
    private fun clampSafe(v: Float, lo: Float, hi: Float): Float =
        if (hi <= lo) (lo + hi) / 2f else v.coerceIn(lo, hi)

    // ── Touch: assign each pointer to the mallet on its side ───────────────────
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                assignPointer(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = 1f / 60f
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i); val y = e.getY(i)
                    if (id == p1Pointer) moveMallet(p1, x, y, topHalf = false, dt)
                    if (!vsAi && id == p2Pointer) moveMallet(p2, x, y, topHalf = true, dt)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == p1Pointer) { p1Pointer = -1; p1.vx = 0f; p1.vy = 0f }
                if (id == p2Pointer) { p2Pointer = -1; p2.vx = 0f; p2.vy = 0f }
            }
        }
        return true
    }

    private fun assignPointer(id: Int, x: Float, y: Float) {
        // You must GRAB your mallet (touch down on or near it) to control it —
        // tapping empty space no longer teleports the mallet there.
        val grab = malletR * 1.6f   // forgiving grab radius
        if (y >= midY && p1Pointer == -1 && hypot(x - p1.x, y - p1.y) <= grab) {
            p1Pointer = id
        } else if (y < midY && !vsAi && p2Pointer == -1 && hypot(x - p2.x, y - p2.y) <= grab) {
            p2Pointer = id
        }
    }

    private fun moveMallet(m: Mallet, x: Float, y: Float, topHalf: Boolean, dt: Float) {
        val px = m.x; val py = m.y
        m.x = x; m.y = y
        constrainMallet(m, topHalf)
        m.vx = (m.x - px) / dt
        m.vy = (m.y - py) / dt
    }

    // ── Render ────────────────────────────────────────────────────────────────
    override fun onDraw(c: Canvas) {
        if (transparent) {
            // Floating mode: let the home screen / other apps show through. Draw only
            // a faint tint so the play area is readable, plus a bright rink border.
            c.drawColor(Color.argb(70, 6, 20, 28))
            c.drawRoundRect(left, top, right, bottom, dp(18f), dp(18f),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = dp(4f); color = Color.parseColor("#3FA9C9")
                })
        } else {
            c.drawColor(Color.parseColor("#06141C"))
            // Table felt
            c.drawRoundRect(left, top, right, bottom, dp(18f), dp(18f), feltPaint)
        }
        // Center line + circle
        c.drawLine(left, midY, right, midY, linePaint)
        c.drawCircle(centerX, midY, dp(70f), linePaint)
        // Goals (dark gaps in the end walls)
        c.drawRect(centerX - goalHalf, top - wall, centerX + goalHalf, top + dp(4f), goalPaint)
        c.drawRect(centerX - goalHalf, bottom - dp(4f), centerX + goalHalf, bottom + wall, goalPaint)
        // Goal posts (accent)
        c.drawLine(centerX - goalHalf, top, centerX - goalHalf, top + dp(2f), linePaint)

        // Scores, faint, mirrored for each player
        scorePaint.alpha = 50
        c.save(); c.rotate(180f, centerX, height * 0.27f)
        c.drawText("$score2", centerX, height * 0.27f, scorePaint); c.restore()
        c.drawText("$score1", centerX, height * 0.75f, scorePaint)

        drawPuck(c)
        drawMallet(c, p1)
        drawMallet(c, p2)

        if (goalFlashSec > 0f) {
            flashPaint.alpha = (goalFlashSec / 0.7f * 90f).toInt().coerceIn(0, 90)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }
    }

    private fun drawPuck(c: Canvas) {
        c.drawCircle(puckX, puckY, puckR, puckPaint)
        c.drawCircle(puckX, puckY, puckR, puckRim)
    }

    private fun drawMallet(c: Canvas, m: Mallet) {
        val grad = RadialGradient(
            m.x, m.y - malletR * 0.3f, malletR,
            m.topColor, m.botColor, Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
        c.drawCircle(m.x, m.y, malletR, p)
        // Knob
        c.drawCircle(m.x, m.y, malletR * 0.45f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) })
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
