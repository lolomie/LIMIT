package dev.limit.app.nudity

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Constructed by the already user-enabled AccessibilityService only.
 * Existing time-limit overlay retains priority; Android system dialogs must stay usable.
 */
object NudityProtectionOverlay {
    fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    fun create(context: Context, goHome: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(24, 28, 38))
            setPadding(32, 24, 32, 24)
            addView(TextView(context).apply {
                text = "LIMIT"
                setTextColor(Color.WHITE)
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "LIMIT hat möglicherweise Nacktheit erkannt."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(12, 32, 12, 32)
            })
            addView(Button(context).apply {
                text = "Zur Startseite"
                setOnClickListener { goHome() }
            })
        }
}
