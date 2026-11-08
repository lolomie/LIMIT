package dev.limit.app

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.limit.app.monitor.ProtectionService
import dev.limit.app.data.ServiceDiagnostics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.limit.app.ui.LimitApp

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val app = application as LimitApplication
        if (app.tracker.isAccessibilityEnabled()) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, ProtectionService::class.java))
            } catch (error: Exception) { ServiceDiagnostics.record(this, "protection_launch", error) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LimitApplication
        setContent { LimitApp(app.repository, app.tracker) }
    }
}
