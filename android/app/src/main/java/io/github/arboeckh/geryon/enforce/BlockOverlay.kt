package io.github.arboeckh.geryon.enforce

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.arboeckh.geryon.block.Decision
import io.github.arboeckh.geryon.block.Layer

/**
 * Full-screen block notice drawn by the accessibility service itself
 * (TYPE_ACCESSIBILITY_OVERLAY), so it covers a blocked app the moment it appears,
 * with no activity launch in between and no overlay permission needed.
 */
class BlockOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    /** Package the overlay is currently shown for, or null if hidden. */
    var shownFor: String? = null
        private set

    fun show(pkg: String, name: String, decision: Decision) {
        hide()
        val v = build(name, reason(decision))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        windowManager.addView(v, params)
        view = v
        shownFor = pkg
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        shownFor = null
    }

    private fun build(name: String, reason: String?): View {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }

        return LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Fully opaque: the blocked app opening and closing underneath must not show through.
            setBackgroundColor(Color.rgb(18, 18, 20))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) hide()
                keyCode == KeyEvent.KEYCODE_BACK
            }

            addView(TextView(themed).apply {
                text = "$name is blocked"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                gravity = Gravity.CENTER
            })
            if (reason != null) {
                addView(TextView(themed).apply {
                    text = reason
                    setTextColor(Color.argb(200, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(32))
                })
            }
            addView(Button(themed).apply {
                text = "OK"
                setOnClickListener { hide() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun reason(decision: Decision): String? = when (decision.layer) {
        Layer.Hard -> "${decision.sourceName ?: "This category"} is always blocked."
        Layer.Profile, Layer.Base -> "Blocked by the ${decision.sourceName ?: "active"} profile. Scan a grant tag to unlock it for a while."
        else -> null
    }
}
