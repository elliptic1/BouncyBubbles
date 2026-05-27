package app.toddbsmith.bouncybubbles.physics

import android.graphics.Color

/**
 * Curated set of vibrant gradient pairs. Top is the light/highlight color
 * (toward the upper-left of the bubble), bottom is the saturated bottom-right.
 *
 * Chosen for visual punch on dark + light backgrounds and good distinction
 * between adjacent bubbles in a crowded scene.
 */
object BubblePalette {
    data class Pair(val top: Int, val bottom: Int)

    val pairs: List<Pair> = listOf(
        Pair(Color.parseColor("#FFB3C7"), Color.parseColor("#E91E63")), // pink
        Pair(Color.parseColor("#FFE082"), Color.parseColor("#FB8C00")), // amber/orange
        Pair(Color.parseColor("#A5D6A7"), Color.parseColor("#2E7D32")), // green
        Pair(Color.parseColor("#90CAF9"), Color.parseColor("#1565C0")), // blue
        Pair(Color.parseColor("#CE93D8"), Color.parseColor("#6A1B9A")), // purple
        Pair(Color.parseColor("#80DEEA"), Color.parseColor("#00838F")), // cyan/teal
        Pair(Color.parseColor("#FFCC80"), Color.parseColor("#E65100")), // tangerine
        Pair(Color.parseColor("#F48FB1"), Color.parseColor("#AD1457")), // rose
        Pair(Color.parseColor("#B39DDB"), Color.parseColor("#4527A0")), // indigo
        Pair(Color.parseColor("#FFF59D"), Color.parseColor("#F9A825")), // sunshine
        Pair(Color.parseColor("#BCAAA4"), Color.parseColor("#5D4037")), // mocha
        Pair(Color.parseColor("#EF9A9A"), Color.parseColor("#C62828")), // red
    )

    fun random(): Pair = pairs.random()
}
