package dev.limit.app.nudity

import android.content.Context

/** Separate preferences: existing daily budgets, exception ledgers and schema remain untouched. */
class NudityPreferences(context: Context) {
    private val settings = context.applicationContext
        .getSharedPreferences("limit_nudity_v1", Context.MODE_PRIVATE)

    var aiEnabled: Boolean
        get() = settings.getBoolean("ai_enabled", false)
        set(value) { settings.edit().putBoolean("ai_enabled", value).apply() }

    var fallbackEnabled: Boolean
        get() = settings.getBoolean("fallback_enabled", false)
        set(value) { settings.edit().putBoolean("fallback_enabled", value).apply() }

    var sensitivity: NuditySensitivity
        get() = NuditySensitivity.entries.firstOrNull {
            it.name == settings.getString("sensitivity", NuditySensitivity.BALANCED.name)
        } ?: NuditySensitivity.BALANCED
        set(value) { settings.edit().putString("sensitivity", value.name).apply() }

    var fallbackPackages: Set<String>
        get() = settings.getStringSet("fallback_packages", emptySet())?.toSet() ?: emptySet()
        set(value) { settings.edit().putStringSet("fallback_packages", value.toSet()).apply() }
}
