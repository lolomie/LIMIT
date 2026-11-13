package dev.limit.app.nudity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * GantMan MobileNetV2 (MIT), input 224x224 RGB float32 / 255, classes
 * drawings, hentai, neutral, porn, sexy. Only index 3 is considered.
 * Generic porn classification is NOT a precise nudity/body-part detector.
 */
class NudityClassifier(context: Context) : AutoCloseable {
    private val model = context.assets.open("nsfw.tflite").use { input ->
        val bytes = input.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val inputBuffer = ByteBuffer.allocateDirect(4 * SIDE * SIDE * 3).order(ByteOrder.nativeOrder())
    private val probabilities = Array(1) { FloatArray(NudityPolicy.CLASS_COUNT) }
    private val pixels = IntArray(SIDE * SIDE)
    private val tile = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(tile)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, SIDE, SIDE, 3))) {
            "Unexpected model input shape"
        }
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 5))) {
            "Unexpected model output shape"
        }
    }

    /** Whole-screen protection; analyze top, center and bottom to reduce missed small content. */
    fun score(frame: Bitmap): Float {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return 0f
        var highest = 0f
        val sampleHeight = minOf(h, w)
        val starts = if (h > w) listOf(0, (h - sampleHeight) / 2, h - sampleHeight).distinct()
                     else listOf(0)
        for (top in starts) {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(frame, Rect(0, top, w, top + sampleHeight),
                Rect(0, 0, SIDE, SIDE), paint)
            tile.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            inputBuffer.clear()
            pixels.forEach { argb ->
                inputBuffer.putFloat(((argb shr 16) and 255) / 255f)
                inputBuffer.putFloat(((argb shr 8) and 255) / 255f)
                inputBuffer.putFloat((argb and 255) / 255f)
            }
            inputBuffer.rewind()
            interpreter.run(inputBuffer, probabilities)
            highest = maxOf(highest, NudityPolicy.nudityProxy(probabilities[0]))
        }
        return highest
    }

    override fun close() {
        interpreter.close()
        tile.recycle()
    }

    private companion object { const val SIDE = 224 }
}
