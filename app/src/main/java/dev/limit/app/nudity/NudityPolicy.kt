package dev.limit.app.nudity

/** Strictly opt-in; all decisions are local and never grant extra app time. */
enum class NuditySensitivity(val threshold: Float) {
    STRICT(0.95f), BALANCED(0.85f), SENSITIVE(0.70f)
}

/** Two positive observations are required; a missing frame is not a positive. */
class NuditySmoother(private val requiredHits: Int = 2) {
    private var hits = 0
    fun observe(confidence: Float, threshold: Float): Boolean {
        require(threshold in 0f..1f)
        hits = if (confidence.isFinite() && confidence >= threshold) {
            (hits + 1).coerceAtMost(requiredHits)
        } else 0
        return hits >= requiredHits
    }
    fun reset() { hits = 0 }
}

/** No broad NSFW labels: only the model's Porn output is considered.
 * This is still an imperfect proxy for actual exposed body parts.
 */
object NudityPolicy {
    const val PORN_INDEX = 3
    const val CLASS_COUNT = 5
    fun nudityProxy(scores: FloatArray): Float {
        require(scores.size == CLASS_COUNT)
        return scores[PORN_INDEX].takeIf { it.isFinite() && it in 0f..1f } ?: 0f
    }
    fun fallbackBlocks(enabled: Boolean, captureActive: Boolean,
                       selected: Set<String>, foreground: String?): Boolean =
        enabled && !captureActive && foreground != null && foreground in selected
}
