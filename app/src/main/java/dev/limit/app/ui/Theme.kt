package dev.limit.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF5544A3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF),
    onPrimaryContainer = Color(0xFF30205E),
    background = Color(0xFFF8F7FB),
    onBackground = Color(0xFF201F29),
    surface = Color(0xFFF8F7FB),
    onSurface = Color(0xFF201F29),
    surfaceVariant = Color(0xFFEEEAF5),
    onSurfaceVariant = Color(0xFF655F72),
    outline = Color(0xFF80788D),
    outlineVariant = Color(0xFFDDD7E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCABDFF),
    onPrimary = Color(0xFF30205E),
    primaryContainer = Color(0xFF43337B),
    onPrimaryContainer = Color(0xFFE9E3FF),
    background = Color(0xFF141319),
    onBackground = Color(0xFFEAE5F0),
    surface = Color(0xFF141319),
    onSurface = Color(0xFFEAE5F0),
    surfaceVariant = Color(0xFF29252F),
    onSurfaceVariant = Color(0xFFC0B7CD),
    outline = Color(0xFF978CA7),
    outlineVariant = Color(0xFF443C50),
)

@Composable
fun LimitTheme(preference: String, content: @Composable () -> Unit) {
    val dark = when (preference) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    val activity = LocalContext.current.findActivity()
    SideEffect {
        activity?.let { owner ->
            owner.runOnUiThread {
                if (!owner.isDestroyed) {
                    WindowCompat.getInsetsController(owner.window, owner.window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
