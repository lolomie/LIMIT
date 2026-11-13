package dev.limit.app.nudity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NudityPhase { INACTIVE, CONSENT_REQUIRED, STARTING, ACTIVE, ERROR }
data class NuditySessionSnapshot(val phase: NudityPhase = NudityPhase.INACTIVE,
                                 val explanation: String? = null)

/** Ephemeral reality, never persisted or restored after process/device restart. */
object NuditySession {
    private val mutable = MutableStateFlow(NuditySessionSnapshot())
    val status: StateFlow<NuditySessionSnapshot> = mutable.asStateFlow()
    fun set(phase: NudityPhase, explanation: String? = null) {
        mutable.value = NuditySessionSnapshot(phase, explanation?.take(140))
        if (phase != NudityPhase.ACTIVE) {
            NudityBlockCoordinator.clearRequest()
            NudityDiagnostics.reset()
        }
    }
}

/** In-memory counters only. No image, pixel, URL, or app-name retention. */
data class NudityDiagnosticsSnapshot(
    val framesAnalyzed: Long = 0,
    val lastPornScore: Float? = null,
    val maximumPornScore: Float = 0f,
    val blockRequests: Long = 0,
    val skippedFrames: Long = 0,
)

object NudityDiagnostics {
    private val mutable = MutableStateFlow(NudityDiagnosticsSnapshot())
    val status: StateFlow<NudityDiagnosticsSnapshot> = mutable.asStateFlow()
    fun analyzed(score: Float, blocked: Boolean) {
        val old = mutable.value
        mutable.value = old.copy(
            framesAnalyzed = old.framesAnalyzed + 1,
            lastPornScore = score.takeIf { it.isFinite() },
            maximumPornScore = if (score.isFinite()) maxOf(old.maximumPornScore, score) else old.maximumPornScore,
            blockRequests = old.blockRequests + if (blocked) 1 else 0
        )
    }
    fun skipped() {
        val old = mutable.value
        mutable.value = old.copy(skippedFrames = old.skippedFrames + 1)
    }
    fun reset() { mutable.value = NudityDiagnosticsSnapshot() }
}

/** Bridges optional content protection into LIMIT's existing accessibility blocker.
 * The normal daily-limit overlay always has precedence.
 */
object NudityBlockCoordinator {
    @Volatile var foregroundPackage: String? = null
        private set
    private val mutableRequest = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = mutableRequest.asStateFlow()

    fun onForegroundChanged(packageName: String?) {
        if (foregroundPackage != packageName) {
            foregroundPackage = packageName
            mutableRequest.value = null
        }
    }

    fun flag(packageName: String) {
        if (foregroundPackage == packageName && NuditySession.status.value.phase == NudityPhase.ACTIVE)
            mutableRequest.value = packageName
    }

    fun clearRequest() { mutableRequest.value = null }
}
