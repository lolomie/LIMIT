package dev.limit.app.core

import kotlin.math.roundToInt

/** Display coordinates, independent of Android so placement can be regression tested. */
data class CapsuleBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class CameraCapsulePlacement(
    val window: CapsuleBounds,
    val text: CapsuleBounds,
    val camera: CapsuleBounds,
)

object CameraCapsuleGeometry {
    /** The result's text bounds are relative to [CameraCapsulePlacement.window]. */
    fun place(
        displayWidth: Int,
        displayHeight: Int,
        statusBarHeight: Int,
        cameraBounds: CapsuleBounds?,
        textWidth: Int,
        textHeight: Int,
        density: Float,
    ): CameraCapsulePlacement {
        val width = displayWidth.coerceAtLeast(1)
        val height = displayHeight.coerceAtLeast(1)
        fun dp(value: Int) = (value * density.coerceAtLeast(0.1f)).roundToInt().coerceAtLeast(1)
        val holeSize = dp(20).coerceAtMost(width).coerceAtMost(height)
        val fallbackX = width / 2
        val fallbackY = statusBarHeight.coerceAtLeast(holeSize) / 2
        val fallback = CapsuleBounds(
            (fallbackX - holeSize / 2).coerceIn(0, width - holeSize),
            (fallbackY - holeSize / 2).coerceIn(0, height - holeSize),
            (fallbackX - holeSize / 2).coerceIn(0, width - holeSize) + holeSize,
            (fallbackY - holeSize / 2).coerceIn(0, height - holeSize) + holeSize,
        )
        val cutout = cameraBounds?.let {
            CapsuleBounds(it.left.coerceIn(0, width), it.top.coerceIn(0, height),
                it.right.coerceIn(0, width), it.bottom.coerceIn(0, height))
        }?.takeIf { it.width > 0 && it.height > 0 } ?: fallback
        // OEM cutout rectangles include the area from the display edge, not just the lens.
        // Use them as an anchor, never as the glass pill's size or vertical centre.
        val lensSize = cutout.width.coerceIn(dp(12), dp(20)).coerceAtMost(minOf(width, height))
        val centreX = (cutout.left + cutout.right) / 2
        val centreY = if (cutout.top < statusBarHeight) fallbackY else (cutout.top + cutout.bottom) / 2
        val cameraLeft = (centreX - lensSize / 2).coerceIn(0, width - lensSize)
        val cameraTop = (centreY - lensSize / 2).coerceIn(0, height - lensSize)
        val camera = CapsuleBounds(cameraLeft, cameraTop, cameraLeft + lensSize, cameraTop + lensSize)
        val gap = dp(4)
        val padding = dp(6)
        val requestedTextWidth = textWidth.coerceAtLeast(1)
        val requestedTextHeight = textHeight.coerceAtLeast(1).coerceAtMost(height)
        val rightRoom = (width - camera.right - gap - 2 * padding).coerceAtLeast(0)
        val leftRoom = (camera.left - gap - 2 * padding).coerceAtLeast(0)
        // Keep the crowded right-hand network/battery icons clear on centred-camera phones.
        // For edge cameras or very long labels, fall back to whichever side fits better.
        val onRight = leftRoom < requestedTextWidth && rightRoom > leftRoom
        val visibleTextWidth = requestedTextWidth.coerceAtMost(if (onRight) rightRoom else leftRoom)
        val windowWidth = (visibleTextWidth.toLong() + 2L * padding)
            .coerceAtMost(width.toLong()).toInt().coerceAtLeast(1)
        val windowHeight = maxOf(dp(20), requestedTextHeight + dp(4))
            .coerceAtMost(height)
        val windowLeft = (if (onRight) camera.right + gap else camera.left - gap - windowWidth)
            .coerceIn(0, width - windowWidth)
        val windowTop = ((camera.top + camera.bottom - windowHeight) / 2)
            .coerceIn(0, height - windowHeight)
        val textLeft = (windowLeft + padding).coerceAtMost(windowLeft + windowWidth - visibleTextWidth)
        val textTop = ((camera.top + camera.bottom - requestedTextHeight) / 2)
            .coerceIn(windowTop, windowTop + windowHeight - requestedTextHeight)
        return CameraCapsulePlacement(
            window = CapsuleBounds(windowLeft, windowTop, windowLeft + windowWidth, windowTop + windowHeight),
            text = CapsuleBounds(textLeft - windowLeft, textTop - windowTop,
                textLeft - windowLeft + visibleTextWidth, textTop - windowTop + requestedTextHeight),
            camera = camera,
        )
    }
}
