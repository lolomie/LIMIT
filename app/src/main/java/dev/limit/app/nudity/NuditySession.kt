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
        if (phase != NudityPhase.ACTIVE) NudityBlockCoordinator.clearRequest()
    }
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
