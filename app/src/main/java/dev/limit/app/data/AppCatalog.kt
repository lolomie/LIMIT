package dev.limit.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import java.text.Collator

data class AppEntry(val packageName: String, val label: String, val protected: Boolean)

class AppCatalog(private val context: Context) {
    private val manager = context.packageManager

    @Suppress("DEPRECATION")
    fun load(): List<AppEntry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protected = protectedPackages()
        val collator = Collator.getInstance()
        return manager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                AppEntry(info.packageName, runCatching { result.loadLabel(manager).toString() }
                    .getOrDefault(info.packageName), info.packageName in protected)
            }.distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    @Suppress("DEPRECATION")
    fun homePackages(): Set<String> = manager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
    ).mapNotNull { it.activityInfo?.packageName }.toSet()

    fun protectedPackages(): Set<String> = buildSet {
        add(context.packageName)
        addAll(homePackages())
        addAll(setOf("android", "com.android.settings", "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.dialer", "com.miui.securitycenter"))
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
            .getOrElse { ServiceDiagnostics.record(context, "dialer_catalog", it); null }?.let(::add)
    }
}
