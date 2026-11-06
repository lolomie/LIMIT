package dev.limit.app.monitor

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.limit.app.core.CameraCapsuleGeometry
import dev.limit.app.core.CapsuleBounds
import dev.limit.app.core.CapsuleObservation
import dev.limit.app.core.UsageCapsuleModel
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One passive, app-local accessibility window. Presentation failures never escape into enforcement. */
class UsageCapsuleController(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: FrameLayout? = null
    private var cutoutBounds: List<CapsuleBounds> = emptyList()
    private var minutesView: TextView? = null
    private var renderedPackage: String? = null
    private var params: WindowManager.LayoutParams? = null
    private var minuteJob: Job? = null

    fun show(observation: CapsuleObservation, stillEligible: () -> Boolean): Boolean {
        minuteJob?.cancel()
        return try {
            if (!stillEligible()) { hide(); return false }
            if (view == null || renderedPackage != observation.packageName) {
                hide()
                create(observation.packageName)
            }
            fun render(): Long? {
                val frame = UsageCapsuleModel.project(observation, SystemClock.elapsedRealtime(),
                    LocalDate.now().toString(), if (stillEligible()) observation.packageName else null,
                    screenUsable = true, available = true, blocking = false) ?: return null
                val text = "${frame.minutes} Min."
                if (minutesView?.text?.toString() != text) {
                    minutesView?.text = text
                    updatePlacement()
                }
                view?.contentDescription = "LIMIT usage: ${frame.packageName}; ${frame.minutes} Min."
                return frame.nextUpdateMs
            }
            val next = render() ?: run { hide(); return false }
            minuteJob = scope.launch {
                var wait = next
                while (true) {
                    delay(wait)
                    try {
                        wait = render() ?: run { hide(); return@launch }
                    } catch (_: Exception) {
                        hide()
                        return@launch
                    }
                }
            }
            true
        } catch (_: Exception) {
            hide()
            false
        }
    }

    private fun create(packageName: String) {
        val row = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x30FFFFFF, 0x18616B7A, 0x24FFFFFF),
            ).apply {
                cornerRadius = dp(100).toFloat()
                setStroke(dp(1).coerceAtLeast(1), 0x50FFFFFF)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = false
            isFocusable = false
            minimumHeight = dp(20)
        }
        minutesView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(dp(2).toFloat(), 0f, context.resources.displayMetrics.density * 0.5f,
                0xD9000000.toInt())
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(minutesView, FrameLayout.LayoutParams(-2, -2))
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "LIMIT usage capsule"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        row.setOnApplyWindowInsetsListener { _, insets ->
            runCatching {
                // Insets belong to this small window. Convert bounds to display coordinates.
                if (Build.VERSION.SDK_INT >= 28) {
                    insets.displayCutout?.boundingRects?.takeIf { it.isNotEmpty() }?.let { bounds ->
                        cutoutBounds = bounds.map { it.toBounds(layout.x, layout.y) }
                    }
                }
                updatePlacement()
            }.onFailure { hide() }
            insets
        }
        // Retain the view before addView so even a partially failed attachment can be removed.
        view = row
        params = layout
        renderedPackage = packageName
        updatePlacement(attached = false)
        manager.addView(row, layout)
        row.requestApplyInsets()
    }

    fun refreshPlacement() {
        val row = view ?: return
        runCatching {
            // TextView stores pixels: resolve sp again after a live font-scale change.
            minutesView?.textSize = 11f
            cutoutBounds = emptyList()
            updatePlacement()
            row.requestApplyInsets()
        }.onFailure { hide() }
    }

    @Suppress("DEPRECATION")
    private fun updatePlacement(attached: Boolean = true) {
        val row = view ?: return
        val text = minutesView ?: return
        val layout = params ?: return
        val displayWidth: Int
        val displayHeight: Int
        val statusBarHeight: Int
        val candidates: List<CapsuleBounds>
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            displayWidth = metrics.bounds.width()
            displayHeight = metrics.bounds.height()
            statusBarHeight = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                .takeIf { it > 0 } ?: fallbackStatusBarHeight()
            candidates = metrics.windowInsets.displayCutout?.boundingRects
                ?.map { it.toBounds() }?.takeIf { it.isNotEmpty() } ?: cutoutBounds
        } else {
            val metrics = DisplayMetrics()
            manager.defaultDisplay.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            statusBarHeight = fallbackStatusBarHeight()
            candidates = cutoutBounds
        }
        // Ignore broad notches/waterfall edges. A camera cutout is small and meets a display edge.
        val camera = candidates.filter {
            it.width in 1..minOf(dp(80), displayWidth / 3) && it.height in 1..dp(100) &&
                (it.top <= statusBarHeight || it.left <= statusBarHeight ||
                    it.right >= displayWidth - statusBarHeight)
        }.minByOrNull { it.width.toLong() * it.height }
        text.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val placement = CameraCapsuleGeometry.place(displayWidth, displayHeight, statusBarHeight,
            camera, text.measuredWidth, text.measuredHeight, context.resources.displayMetrics.density)
        val textLayout = text.layoutParams as FrameLayout.LayoutParams
        if (textLayout.width != placement.text.width || textLayout.height != placement.text.height ||
            textLayout.leftMargin != placement.text.left || textLayout.topMargin != placement.text.top) {
            textLayout.width = placement.text.width
            textLayout.height = placement.text.height
            textLayout.leftMargin = placement.text.left
            textLayout.topMargin = placement.text.top
            text.layoutParams = textLayout
        }
        val bounds = placement.window
        if (layout.x != bounds.left || layout.y != bounds.top ||
            layout.width != bounds.width || layout.height != bounds.height) {
            layout.x = bounds.left
            layout.y = bounds.top
            layout.width = bounds.width
            layout.height = bounds.height
            if (attached) manager.updateViewLayout(row, layout)
        }
    }

    fun hide() {
        minuteJob?.cancel()
        minuteJob = null
        view?.let { row -> runCatching { manager.removeViewImmediate(row) } }
        view = null
        minutesView = null
        renderedPackage = null
        params = null
        cutoutBounds = emptyList()
    }

    @Suppress("DiscouragedApi")
    private fun fallbackStatusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun Rect.toBounds(offsetX: Int = 0, offsetY: Int = 0) =
        CapsuleBounds(left + offsetX, top + offsetY, right + offsetX, bottom + offsetY)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
