package dev.limit.app.nudity

/** Model confidence thresholds: exploratory defaults, NOT calibrated probabilities. */
enum class NuditySensitivity(val threshold: Float) {
    STRICT(0.65f), BALANCED(0.48f), SENSITIVE(0.33f)
}

class NuditySmoother(private val requiredHits: Int = 2) {
    init { require(requiredHits > 0) }
    private var hits = 0
    fun observe(confidence: Float, threshold: Float): Boolean {
        require(threshold in 0f..1f)
        hits = if (confidence.isFinite() && confidence >= threshold)
            (hits + 1).coerceAtMost(requiredHits) else 0
        return hits >= requiredHits
    }
    fun reset() { hits = 0 }
}

/**
 * NudeNet 320n YOLOv8 outputs 18 categories, only exposed anatomical regions count.
 * Covered genitals/breasts/buttocks, skin, face, belly and male chest NEVER trigger.
 * Class order pinned to upstream NudeNet labels.
 */
object NudityPolicy {
    const val CLASS_COUNT = 18
    const val CHANNELS = CLASS_COUNT + 4
    val exposedClassIds: Set<Int> = setOf(2, 3, 4, 6, 14)
    // 2=buttocks exposed, 3=female breast exposed, 4=female genitalia exposed,
    // 6=anus exposed, 14=male genitalia exposed.

    /** [channel][anchor], after YOLO object detection inference. */
    fun exposedScore(channels: Array<FloatArray>): Float {
        require(channels.size == CHANNELS) { "Unexpected YOLO output channels" }
        val anchors = channels[0].size
        require(channels.all { it.size == anchors }) { "Mismatched anchors" }
        var best = 0f
        for (anchor in 0 until anchors) {
            var label = -1
            var confidence = 0f
            for (cls in 0 until CLASS_COUNT) {
                val score = channels[cls + 4][anchor]
                if (score.isFinite() && score > confidence) {
                    confidence = score
                    label = cls
                }
            }
            if (label in exposedClassIds) best = maxOf(best, confidence)
        }
        return best
    }

    fun fallbackBlocks(enabled: Boolean, captureActive: Boolean,
                       selected: Set<String>, foreground: String?): Boolean =
        enabled && !captureActive && foreground != null && foreground in selected
}
