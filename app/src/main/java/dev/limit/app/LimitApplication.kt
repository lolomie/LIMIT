package dev.limit.app

import android.app.Application
import dev.limit.app.data.StateRepository
import dev.limit.app.data.ServiceDiagnostics
import dev.limit.app.monitor.UsageTracker

class LimitApplication : Application() {
    lateinit var repository: StateRepository
        private set
    lateinit var tracker: UsageTracker
        private set

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ServiceDiagnostics.record(this, "uncaught", error)
            previousHandler?.uncaughtException(thread, error)
        }
        repository = StateRepository(this)
        tracker = UsageTracker(this, repository)
    }
}
