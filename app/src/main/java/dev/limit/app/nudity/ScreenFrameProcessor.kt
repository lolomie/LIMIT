package dev.limit.app.nudity

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import android.os.PowerManager
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Called from one dedicated HandlerThread. acquireLatestImage skips stale frames.
 * Every Image closes in a finally block. Only ephemeral pixel buffers exist.
 */
class ScreenFrameProcessor(context: Context) : AutoCloseable {
    private val classifier = NudityClassifier(context)
    private val smoother = NuditySmoother()
    private val busy = AtomicBoolean(false)
    private val preferences = NudityPreferences(context)
    private val power = context.getSystemService(PowerManager::class.java)
    private var lastRunMs = 0L
    private var quickScanUntil = 0L
    private var lastPackage: String? = null

    fun onFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        var owned = false
        try {
            val now = SystemClock.elapsedRealtime()
            val interval = if (now < quickScanUntil) 500 else 1000
            if (now - lastRunMs < interval || !busy.compareAndSet(false, true)) {
                NudityDiagnostics.skipped()
                return
            }
            owned = true
            lastRunMs = now
            if (!power.isInteractive) return
            val foreground = NudityBlockCoordinator.foregroundPackage ?: return
            if (NudityBlockCoordinator.requested.value == foreground ||
                NuditySession.status.value.phase != NudityPhase.ACTIVE) return
            if (lastPackage != foreground) {
                smoother.reset()
                lastPackage = foreground
            }
            val bitmap = image.toBitmap() ?: return
            try {
                val score = classifier.score(bitmap)
                if (score >= 0.55f) quickScanUntil = now + 3000
                val shouldBlock = smoother.observe(score, preferences.sensitivity.threshold) &&
                    foreground == NudityBlockCoordinator.foregroundPackage
                NudityDiagnostics.analyzed(score, shouldBlock)
                if (shouldBlock) NudityBlockCoordinator.flag(foreground)
            } finally { bitmap.recycle() }
        } finally {
            image.close()
            if (owned) busy.set(false)
        }
    }

    private fun Image.toBitmap(): Bitmap? {
        if (format != PixelFormat.RGBA_8888) return null
        val plane = planes.firstOrNull() ?: return null
        if (plane.pixelStride != 4 || plane.rowStride < width * 4) return null
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        return try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) padded else {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            }
        } catch (error: Exception) {
            padded.recycle()
            throw error
        }
    }

    override fun close() { classifier.close() }
}
