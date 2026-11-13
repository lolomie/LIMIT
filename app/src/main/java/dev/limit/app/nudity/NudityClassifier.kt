package dev.limit.app.nudity

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Local NudeNet YOLOv8n anatomical exposure detector.
 * Source: amarchandan/Nudenet @ a91f67d (model / accompanying code AGPL-3.0).
 * Float32 CHW [1,3,320,320]; output [1,22,2100] or [1,2100,22].
 * No files are generated and input frames only exist in RAM.
 */
class NudityClassifier(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val session = env.createSession(
        context.assets.open("nudenet-320n.onnx").use { it.readBytes() }, options)
    private val inputName = session.inputNames.first()
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 3 * SIDE * SIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pixels = IntArray(SIDE * SIDE)
    private val tile = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(tile)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val inputShape = session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo
            ?: error("Unexpected model input type")
        check(inputShape.shape.contentEquals(longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong()))) {
            "Unexpected input shape"
        }
    }

    fun score(frame: Bitmap): Float {
        val width = frame.width
        val height = frame.height
        if (width < 1 || height < 1) return 0f

        val side = minOf(width, height)
        // Portrait: independently scan top/middle/bottom. Landscape: left/middle/right.
        val offsets = if (height > width) listOf(0, (height - side) / 2, height - side)
                      else listOf(0, (width - side) / 2, width - side)
        var best = 0f
        for (offset in offsets.distinct()) {
            val src = if (height > width)
                Rect(0, offset, width, offset + side)
                else Rect(offset, 0, offset + side, height)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(frame, src, Rect(0, 0, SIDE, SIDE), paint)
            tile.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            inputBuffer.clear()
            // ONNX layout is CHW, unlike the preceding TFLite model's HWC.
            for (channel in 0..2) {
                val shift = when (channel) { 0 -> 16; 1 -> 8; else -> 0 }
                for (pixel in pixels) inputBuffer.put(((pixel shr shift) and 255) / 255f)
            }
            inputBuffer.flip()
            OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val batch = result[0].value as? Array<Array<FloatArray>>
                        ?: error("Unexpected YOLO output data type")
                    val output = batch.firstOrNull() ?: error("Empty detection output")
                    val normalized = if (output.size == NudityPolicy.CHANNELS) output
                        else if (output.isNotEmpty() && output[0].size == NudityPolicy.CHANNELS)
                            Array(NudityPolicy.CHANNELS) { ch ->
                                FloatArray(output.size) { anchor -> output[anchor][ch] }
                            }
                        else error("Unexpected YOLO output channel layout")
                    best = maxOf(best, NudityPolicy.exposedScore(normalized))
                }
            }
        }
        return best
    }

    override fun close() {
        session.close()
        options.close()
        tile.recycle()
    }

    companion object { const val SIDE = 320 }
}
