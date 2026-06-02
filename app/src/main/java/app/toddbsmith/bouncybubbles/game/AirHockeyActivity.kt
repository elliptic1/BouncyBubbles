package app.toddbsmith.bouncybubbles.game

import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Full-screen air hockey host. Shows a quick mode picker (2-player vs AI),
 * then runs [AirHockeyView]. Immersive: hides system bars so the table
 * uses the whole screen and stray taps don't pull down the nav bar.
 */
class AirHockeyActivity : ComponentActivity() {

    private var gameView: AirHockeyView? = null
    private lateinit var scoreLabel: TextView
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#06141C")) }
        setContentView(root)   // must precede goImmersive(): insetsController is null until the DecorView exists
        goImmersive()
        showModePicker()
    }

    private fun showModePicker() {
        root.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val title = TextView(this).apply {
            text = "Air Hockey"
            setTextColor(Color.parseColor("#5FD0EF"))
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        panel.addView(title)
        panel.addView(menuButton("2 Players") { startGame(vsAi = false) })
        panel.addView(spacer())
        panel.addView(menuButton("1 Player vs Computer") { startGame(vsAi = true) })

        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun startGame(vsAi: Boolean) {
        root.removeAllViews()

        val view = AirHockeyView(
            this,
            vsAi = vsAi,
            onScore = { p1, p2 -> runOnUiThread { scoreLabel.text = "$p2   :   $p1" } },
            onWin = { p1Won -> runOnUiThread { showWinDialog(p1Won, vsAi) } },
        )
        gameView = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // Centered scoreboard overlay (top number is player 2 / AI, mirrored).
        scoreLabel = TextView(this).apply {
            text = "0   :   0"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(scoreLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        view.start()
    }

    private fun showWinDialog(p1Won: Boolean, vsAi: Boolean) {
        gameView?.stop()
        val winner = when {
            vsAi && p1Won -> "You win! 🎉"
            vsAi && !p1Won -> "Computer wins 🤖"
            p1Won -> "Player 1 wins! 🎉"
            else -> "Player 2 wins! 🎉"
        }
        AlertDialog.Builder(this)
            .setTitle(winner)
            .setMessage("First to 7. Play again?")
            .setPositiveButton("Rematch") { _, _ -> startGame(vsAi) }
            .setNegativeButton("Menu") { _, _ -> showModePicker() }
            .setCancelable(false)
            .show()
    }

    private fun menuButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1A1B2E"))
        setPadding(dp(40), dp(20), dp(40), dp(20))
        minWidth = dp(260)
        setOnClickListener { onClick() }
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(16))
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() { super.onPause(); gameView?.stop() }
    override fun onResume() { super.onResume(); gameView?.start() }
    override fun onDestroy() { super.onDestroy(); gameView?.stop() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
