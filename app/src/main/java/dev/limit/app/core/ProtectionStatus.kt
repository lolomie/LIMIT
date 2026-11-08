package dev.limit.app.core

enum class ProtectionStatus(val message: String) {
    SETUP_REQUIRED("Setup required — tap to review permissions"),
    STORAGE_UNAVAILABLE("Protection unavailable — local settings need attention"),
    RECOVERING("Monitoring interrupted — recovery pending; tap for details"),
    DISCONNECTED("Service disconnected — tap to restore protection"),
    RESTING("Screen resting — monitoring resumes when you unlock"),
    ACTIVE("Protection active — app limits are monitored");

    companion object {
        fun evaluate(permissions: Boolean, storageReady: Boolean, failed: Boolean,
                     connected: Boolean, resting: Boolean): ProtectionStatus = when {
            !permissions -> SETUP_REQUIRED
            !storageReady -> STORAGE_UNAVAILABLE
            failed -> RECOVERING
            !connected -> DISCONNECTED
            resting -> RESTING
            else -> ACTIVE
        }
    }
}
