package app.toddbsmith.bouncybubbles.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The persistent FAB + its expandable menu. Single view, single WindowManager
 * window. When the FAB is collapsed, the touch hit-test is restricted to the
 * FAB circle in the top-right corner of the view; the rest of the view is
 * effectively invisible / pass-through (the window itself is sized for the
 * fully-expanded menu, but only a small portion is opaque).
 *
 * Touch handling:
 *  - Tap on FAB (down + up without drag): toggle menu open/closed.
 *  - Drag on FAB: reposition the FAB window. Service handles snap-to-edge on release.
 *  - Tap on menu item: invoke the corresponding callback, close menu.
 *  - Tap elsewhere inside the view: close menu (the service doesn't get
 *    outside-touches because we don't set FLAG_WATCH_OUTSIDE_TOUCH).
 *
 * The "tap outside menu" UX is best handled with a separate background
 * "scrim" overlay that's only attached while the menu is open; see the
 * service for that orchestration.
 */
@SuppressLint("ViewConstructor")
class FabView(
    context: Context,
    private val callbacks: Callbacks,
) : View(context) {

    interface Callbacks {
        /** FAB drag started — at raw screen coords of the touch. */
        fun onFabDragStart(rawX: Float, rawY: Float)
        /** FAB drag moved — at raw screen coords of the touch. */
        fun onFabDragMove(rawX: Float, rawY: Float)
        /** FAB drag ended — service should snap-to-edge. */
        fun onFabDragEnd()
        /** Menu action invoked. */
        fun onMenuAction(action: MenuAction)
    }

    enum class MenuAction { AddBubble, Settings, PopAll, Stop }

    private data class MenuItem(
        val action: MenuAction,
        val label: String,
        val glyph: Glyph,
    )

    private enum class Glyph { Plus, Gear, Pop, Power }

    private val items = listOf(
        MenuItem(MenuAction.AddBubble, "Add bubble", Glyph.Plus),
        MenuItem(MenuAction.PopAll, "Pop all", Glyph.Pop),
        MenuItem(MenuAction.Settings, "Settings", Glyph.Gear),
        MenuItem(MenuAction.Stop, "Stop", Glyph.Power),
    )

    // Layout constants (px).
    private val fabRadiusPx = dp(28f)        // 56dp diameter
    private val fabShadowPx = dp(4f)
    private val itemRadiusPx = dp(22f)       // 44dp diameter
    private val itemSpacingPx = dp(12f)
    private val labelPadPx = dp(12f)
    private val labelHeightPx = dp(34f)

    /** Total view size — must fit the expanded menu plus all labels. */
    val viewWidthPx: Int
    val viewHeightPx: Int

    /** Where the FAB sits inside the view. We keep it at view-bottom-right
     *  so that menu items can stack upward and labels can extend to the LEFT
     *  of the items toward screen-center. */
    private val fabCx: Float
    private val fabCy: Float

    init {
        // Width: FAB + worst-case label width + padding.
        // Height: FAB + N items + spacing.
        val labelMaxWidth = dp(140f)
        viewWidthPx = (fabRadiusPx * 2f + labelMaxWidth + dp(40f)).toInt()
        viewHeightPx =
            (fabRadiusPx * 2f + (itemRadiusPx * 2f + itemSpacingPx) * items.size + dp(24f)).toInt()
        fabCx = viewWidthPx - fabRadiusPx - dp(8f)
        fabCy = viewHeightPx - fabRadiusPx - dp(8f)
    }

    // -------- Menu animation --------

    private var menuOpen = false
    /** 0 = closed, 1 = open. Drives all menu item animations. */
    private var menuT = 0f
    private var menuAnimator: ValueAnimator? = null

    val isMenuOpen: Boolean get() = menuOpen

    fun toggleMenu() {
        if (menuOpen) closeMenu() else openMenu()
    }

    fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        runMenuAnimator(target = 1f, durationMs = 220L)
    }

    fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        runMenuAnimator(target = 0f, durationMs = 180L)
    }

    private fun runMenuAnimator(target: Float, durationMs: Long) {
        menuAnimator?.cancel()
        menuAnimator = ValueAnimator.ofFloat(menuT, target).apply {
            duration = durationMs
            addUpdateListener {
                menuT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // -------- Touch handling --------

    private var pointerDownOnFab = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downViewX = 0f
    private var downViewY = 0f
    private var dragging = false
    private var dragStartTimeMs = 0L
    private val touchSlopPx = dp(8f)
    private val longPressMs = 250L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val vx = event.x
        val vy = event.y
        val rx = event.rawX
        val ry = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val onFab = hypot(vx - fabCx, vy - fabCy) <= fabRadiusPx + dp(4f)
                if (onFab) {
                    pointerDownOnFab = true
                    downRawX = rx
                    downRawY = ry
                    downViewX = vx
                    downViewY = vy
                    dragging = false
                    dragStartTimeMs = SystemClock.uptimeMillis()
                    return true
                }
                // Down somewhere else in the view: only matters if the menu is open.
                if (menuOpen) {
                    val hit = hitTestMenu(vx, vy)
                    if (hit != null) {
                        return true // wait for UP to fire the action
                    }
                    // Tap on dead area inside our window: close.
                    closeMenu()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerDownOnFab) {
                    if (!dragging) {
                        val moved = hypot(rx - downRawX, ry - downRawY)
                        val held = SystemClock.uptimeMillis() - dragStartTimeMs
                        if (moved > touchSlopPx || held > longPressMs) {
                            dragging = true
                            callbacks.onFabDragStart(rx, ry)
                            // If menu was open, close it during drag.
                            if (menuOpen) closeMenu()
                        }
                    }
                    if (dragging) {
                        callbacks.onFabDragMove(rx, ry)
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (pointerDownOnFab) {
                    pointerDownOnFab = false
                    if (dragging) {
                        dragging = false
                        callbacks.onFabDragEnd()
                    } else {
                        // Tap on FAB — toggle menu.
                        toggleMenu()
                    }
                    return true
                }
                if (menuOpen) {
                    val hit = hitTestMenu(vx, vy)
                    if (hit != null) {
                        callbacks.onMenuAction(hit.action)
                        closeMenu()
                        return true
                    }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pointerDownOnFab && dragging) {
                    callbacks.onFabDragEnd()
                }
                pointerDownOnFab = false
                dragging = false
                return true
            }
        }
        return false
    }

    /** Return the menu item under the view coordinates, or null. */
    private fun hitTestMenu(vx: Float, vy: Float): MenuItem? {
        if (menuT < 0.2f) return null
        items.forEachIndexed { index, item ->
            val (cx, cy) = itemCenter(index)
            if (hypot(vx - cx, vy - cy) <= itemRadiusPx + dp(4f)) return item
        }
        return null
    }

    /** Compute on-screen center of menu item [index] (0 = closest to FAB). */
    private fun itemCenter(index: Int): Pair<Float, Float> {
        val verticalGap = itemRadiusPx * 2f + itemSpacingPx
        val cx = fabCx
        val cy = fabCy - fabRadiusPx - itemRadiusPx - dp(8f) - index * verticalGap
        return cx to cy
    }

    // -------- Drawing --------

    private val fabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B9D")
    }
    private val fabShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
    }
    private val fabRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(80, 255, 255, 255)
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1B2E")
    }
    private val itemShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 26, 27, 46)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Menu items (drawn first so the FAB sits on top during the FAB-rotation transition).
        if (menuT > 0.01f) {
            items.forEachIndexed { index, item ->
                drawMenuItem(canvas, item, index)
            }
        }
        drawFab(canvas)
    }

    private fun drawFab(canvas: Canvas) {
        // Soft drop-shadow.
        canvas.drawCircle(fabCx, fabCy + fabShadowPx, fabRadiusPx, fabShadowPaint)
        canvas.drawCircle(fabCx, fabCy, fabRadiusPx, fabPaint)
        canvas.drawCircle(fabCx, fabCy, fabRadiusPx - dp(1f), fabRingPaint)
        // Glyph: bubble cluster when closed, an X when open.
        if (menuT < 0.5f) {
            drawBubbleGlyph(canvas, fabCx, fabCy, fabRadiusPx * 0.55f)
        } else {
            drawCloseGlyph(canvas, fabCx, fabCy, fabRadiusPx * 0.45f)
        }
    }

    private fun drawBubbleGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // Two interlocking circles for a "bubbles" mark.
        val small = r * 0.55f
        canvas.drawCircle(cx + r * 0.25f, cy + r * 0.15f, r * 0.7f, glyphFillPaint)
        // Carve a hole by drawing a smaller offset bubble in FAB color.
        val carve = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6B9D") }
        canvas.drawCircle(cx - r * 0.15f, cy - r * 0.2f, small, carve)
    }

    private fun drawCloseGlyph(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, glyphPaint)
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, glyphPaint)
    }

    private fun drawMenuItem(canvas: Canvas, item: MenuItem, index: Int) {
        // Each item staggers in. Item 0 starts at t=0, item 1 at t=0.10, etc.
        val stagger = 0.10f
        val itemT = ((menuT - index * stagger) / (1f - index * stagger)).coerceIn(0f, 1f)
        if (itemT <= 0.001f) return
        val (cx, cy) = itemCenter(index)
        // Vertical slide-in: starts 20px below final, animates up.
        val rest = (1f - itemT)
        val drawY = cy + rest * dp(16f)
        val alpha = (itemT * 255f).toInt().coerceIn(0, 255)
        val scale = 0.7f + 0.3f * itemT

        // Shadow.
        itemShadowPaint.alpha = (alpha * 0.6f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, drawY + dp(3f), itemRadiusPx * scale, itemShadowPaint)

        // Button bg.
        itemPaint.alpha = alpha
        canvas.drawCircle(cx, drawY, itemRadiusPx * scale, itemPaint)

        // Glyph.
        drawItemGlyph(canvas, item.glyph, cx, drawY, itemRadiusPx * scale * 0.55f, alpha)

        // Label pill to the left of the button.
        if (itemT > 0.4f) {
            val labelAlpha = (((itemT - 0.4f) / 0.6f) * 255f).toInt().coerceIn(0, 255)
            drawLabel(canvas, item.label, cx, drawY, labelAlpha)
        }
    }

    private fun drawItemGlyph(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, r: Float, alpha: Int) {
        glyphPaint.alpha = alpha
        glyphFillPaint.alpha = alpha
        when (glyph) {
            Glyph.Plus -> {
                canvas.drawLine(cx - r, cy, cx + r, cy, glyphPaint)
                canvas.drawLine(cx, cy - r, cx, cy + r, glyphPaint)
            }
            Glyph.Gear -> {
                // Simple gear: filled circle + teeth.
                canvas.drawCircle(cx, cy, r * 0.6f, glyphFillPaint)
                // Inner hole.
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1B2E"); this.alpha = alpha }
                canvas.drawCircle(cx, cy, r * 0.25f, hole)
                // Teeth.
                glyphPaint.strokeWidth = dp(3f)
                for (k in 0 until 6) {
                    val ang = (k * 60f) * Math.PI.toFloat() / 180f
                    val x1 = cx + r * 0.65f * kotlin.math.cos(ang)
                    val y1 = cy + r * 0.65f * kotlin.math.sin(ang)
                    val x2 = cx + r * 1.0f * kotlin.math.cos(ang)
                    val y2 = cy + r * 1.0f * kotlin.math.sin(ang)
                    canvas.drawLine(x1, y1, x2, y2, glyphPaint)
                }
                glyphPaint.strokeWidth = dp(2.5f)
            }
            Glyph.Pop -> {
                // Burst lines radiating from center.
                val n = 8
                for (k in 0 until n) {
                    val ang = (k * (360f / n)) * Math.PI.toFloat() / 180f
                    val x1 = cx + r * 0.35f * kotlin.math.cos(ang)
                    val y1 = cy + r * 0.35f * kotlin.math.sin(ang)
                    val x2 = cx + r * 1.0f * kotlin.math.cos(ang)
                    val y2 = cy + r * 1.0f * kotlin.math.sin(ang)
                    canvas.drawLine(x1, y1, x2, y2, glyphPaint)
                }
                canvas.drawCircle(cx, cy, r * 0.18f, glyphFillPaint)
            }
            Glyph.Power -> {
                // Power symbol: arc with a vertical line through the top.
                val arcR = r * 0.85f
                val rect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
                canvas.drawArc(rect, -60f, 300f, false, glyphPaint)
                canvas.drawLine(cx, cy - r * 1.0f, cx, cy - r * 0.15f, glyphPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, itemCx: Float, itemCy: Float, alpha: Int) {
        val padH = labelPadPx
        val padV = dp(8f)
        val textWidth = labelTextPaint.measureText(text)
        val labelW = textWidth + padH * 2f
        val labelH = labelHeightPx
        // Place the label's right edge to the left of the menu item with a gap.
        val rightEdge = itemCx - itemRadiusPx - dp(10f)
        val leftEdge = rightEdge - labelW
        val top = itemCy - labelH / 2f
        val bottom = itemCy + labelH / 2f

        labelBgPaint.alpha = (alpha * 0.85f).toInt().coerceIn(0, 255)
        val rect = RectF(leftEdge, top, rightEdge, bottom)
        canvas.drawRoundRect(rect, labelH / 2f, labelH / 2f, labelBgPaint)
        labelTextPaint.alpha = alpha
        val textCx = (leftEdge + rightEdge) / 2f
        val textBaseline = itemCy - (labelTextPaint.descent() + labelTextPaint.ascent()) / 2f
        canvas.drawText(text, textCx, textBaseline, labelTextPaint)
    }

    /** Screen-space x-offset (within the window) of the FAB's center. Used by
     *  the service to position the window so the FAB lands at a target screen
     *  coordinate. */
    val fabOffsetX: Float get() = fabCx
    val fabOffsetY: Float get() = fabCy

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        /** Default size of the FAB obstacle radius in dp, used by physics. */
        const val FAB_OBSTACLE_RADIUS_DP = 32f
    }
}
