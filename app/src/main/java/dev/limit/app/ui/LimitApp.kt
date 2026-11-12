package dev.limit.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.limit.app.core.AllowedHours
import dev.limit.app.core.AllowedHoursMode
import dev.limit.app.core.AppRule
import dev.limit.app.core.LimitEngine
import dev.limit.app.core.LimitReachedAction
import dev.limit.app.core.LimitState
import dev.limit.app.data.AppCatalog
import dev.limit.app.data.AppEntry
import dev.limit.app.data.StateRepository
import dev.limit.app.data.ServiceDiagnostics
import dev.limit.app.monitor.LimitAccessibilityService
import dev.limit.app.monitor.UsageTracker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import dev.limit.app.monitor.ProtectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LimitApp(repository: StateRepository, tracker: UsageTracker) {
    val state by repository.state.collectAsState()
    val ready by repository.ready.collectAsState()
    val storageError by repository.error.collectAsState()
    val connected by LimitAccessibilityService.connected.collectAsState()
    val monitoringFailure by LimitAccessibilityService.failure.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var usageAccess by remember { mutableStateOf(tracker.hasUsageAccess()) }
    var accessibilityEnabled by remember { mutableStateOf(tracker.isAccessibilityEnabled()) }
    var usageAvailable by remember { mutableStateOf(false) }
    var usageMessage by remember { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    var resumeEpoch by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var serviceDiagnostics by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeEpoch, monitoringFailure) {
        serviceDiagnostics = withContext(Dispatchers.IO) { ServiceDiagnostics.report(context) }
    }
    LaunchedEffect(ready, resumeEpoch) {
        if (!ready) return@LaunchedEffect
        usageAccess = tracker.hasUsageAccess()
        accessibilityEnabled = tracker.isAccessibilityEnabled()
        try {
            apps = withContext(Dispatchers.IO) { AppCatalog(context).load() }
            appsLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            appsLoaded = true
            snackbar.showSnackbar("Could not load applications. Reopen LIMIT to try again.")
        }
        while (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            usageAccess = tracker.hasUsageAccess()
            accessibilityEnabled = tracker.isAccessibilityEnabled()
            try {
                val snapshot = tracker.snapshot()
                today = snapshot.date
                usageAvailable = snapshot.available
                usageMessage = snapshot.message
                if (snapshot.available) repository.reconcile(snapshot.date, snapshot.totals)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                usageAvailable = false
                usageMessage = "Usage could not be read. Check permissions and reopen LIMIT."
            }
            delay(15_000)
        }
    }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "The change could not be saved. Please try again.")
            }
        }
    }

    suspend fun saveRule(packageName: String, minutes: Int?, hours: AllowedHours? = null) {
        val snapshot = tracker.snapshot()
        if (snapshot.available) repository.reconcile(snapshot.date, snapshot.totals)
        repository.setRule(packageName, minutes, hours)
    }

    fun openSettings(action: String, packageSpecific: Boolean = false) {
        try {
            context.startActivity(Intent(action).apply {
                if (packageSpecific) data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Open Android Settings to configure this permission.") }
        }
    }

    LimitTheme(state.theme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Today", "Apps", "Settings").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { NavigationGlyph(index) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("nav_${label.lowercase(Locale.ROOT)}"),
                        )
                    }
                }
            },
        ) { padding ->
            if (!ready) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(storageError ?: "Opening LIMIT…")
                    }
                }
            } else {
                when (tab) {
                    0 -> Dashboard(
                        state = state,
                        today = today,
                        apps = apps,
                        active = usageAccess && accessibilityEnabled && connected && usageAvailable && storageError == null && monitoringFailure == null,
                        usageAvailable = usageAvailable,
                        message = storageError ?: monitoringFailure ?: usageMessage ?:
                            if (accessibilityEnabled && !connected)
                                "Accessibility service is enabled but disconnected. Review its status in Settings."
                            else null,
                        padding = padding,
                        onSetup = { tab = 2 },
                        onAdd = { tab = 1 },
                        onEdit = { editing = it },
                    )
                    1 -> AppsPage(
                        apps = apps,
                        loaded = appsLoaded,
                        rules = state.rules,
                        padding = padding,
                        onEdit = { editing = it },
                        onRemove = { app -> perform { saveRule(app.packageName, null) } },
                    )
                    else -> SettingsPage(
                        state = state,
                        usageAccess = usageAccess,
                        accessibilityEnabled = accessibilityEnabled,
                        connected = connected,
                        storageError = storageError ?: monitoringFailure,
                        padding = padding,
                        onUsage = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS, true) },
                        onAccessibility = { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                        onAppSettings = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                        onTheme = { theme -> perform { repository.setTheme(theme) } },
                        onLimitReachedAction = { action -> perform { repository.setLimitReachedAction(action) } },
                        serviceDiagnostics = serviceDiagnostics,
                        onCopyDiagnostics = {
                            perform {
                                val report = withContext(Dispatchers.IO) {
                                    ServiceDiagnostics.report(context) +
                                        "Usage access: ${tracker.hasUsageAccess()}\n" +
                                        "Accessibility enabled: ${tracker.isAccessibilityEnabled()}\n" +
                                        "Service connected: ${tracker.isServiceConnected()}\n"
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("LIMIT service diagnostics", report))
                                snackbar.showSnackbar("Service diagnostics copied.")
                            }
                        },
                    )
                }
            }
        }
        editing?.let { app ->
            LimitDialog(
                app = app,
                currentRule = state.rules[app.packageName] ?: AppRule(30),
                onDismiss = { editing = null },
                onSave = { rule ->
                    perform { saveRule(app.packageName, rule.limitMinutes, rule.hours) }
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun Dashboard(
    state: LimitState,
    today: String,
    apps: List<AppEntry>,
    active: Boolean,
    usageAvailable: Boolean,
    message: String?,
    padding: PaddingValues,
    onSetup: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AppEntry) -> Unit,
) {
    val selected = state.rules.filterValues { it.enabled }
    val grantsLeft = (2 - (state.days[today]?.grantsUsed ?: 0)).coerceIn(0, 2)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("dashboard"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("LIMIT", fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Spacer(Modifier.height(8.dp))
                Text("Make time for what matters.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                val dateText = runCatching {
                    LocalDate.parse(today).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                }.getOrDefault(today)
                Text(dateText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onSetup),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        Text(if (active) "LIMIT is active" else "Monitoring is inactive", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(
                        if (active) "Your selected apps will be blocked at their daily limits."
                        else message ?: "Complete permission setup to track usage and block apps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!active) Text("Review setup →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Daily exceptions", style = MaterialTheme.typography.titleMedium)
                    Text("Five extra minutes, for one app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("$grantsLeft / 2", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = "$grantsLeft of 2 exceptions remaining today" })
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Your limits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onAdd) { Text("Edit apps") }
            }
        }
        if (selected.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A small limit. A little more space.", style = MaterialTheme.typography.titleMedium)
                        Text("Choose the apps you want to spend less time in, then give each one a daily allowance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onAdd) { Text("Choose apps") }
                    }
                }
            }
        }
        items(selected.keys.sortedBy { pkg -> apps.find { it.packageName == pkg }?.label ?: pkg }, key = { it }) { pkg ->
            val app = apps.find { it.packageName == pkg } ?: AppEntry(pkg, pkg, false)
            val rule = selected.getValue(pkg)
            val budget = LimitEngine.budget(state, today, pkg)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(app) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppMonogram(app.label)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${rule.hours.label} · ${rule.limitMinutes} Min./Tag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        if (!usageAvailable) "Usage unavailable" else if (budget.remainingMs == 0L) "Daily limit reached" else "${duration(budget.remainingMs)} left",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (usageAvailable) {
                        val total = budget.usedMs.toDouble() + budget.remainingMs.toDouble()
                        LinearProgressIndicator(
                            progress = { if (total > 0) (budget.usedMs / total).toFloat().coerceIn(0f, 1f) else 1f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        )
                        Text(
                            "${duration(budget.usedMs)} used today" + if (budget.bonusRemainingMs > 0) " · ${duration(budget.bonusRemainingMs)} exception credit" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Text("Allowances reset at local midnight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
    }
}

@Composable
private fun AppsPage(
    apps: List<AppEntry>,
    loaded: Boolean,
    rules: Map<String, AppRule>,
    padding: PaddingValues,
    onEdit: (AppEntry) -> Unit,
    onRemove: (AppEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("apps"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeading("Choose your apps", "Set a daily allowance for each distraction.") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("app_search"),
                shape = RoundedCornerShape(16.dp),
            )
        }
        item { Text("${rules.count { it.value.enabled }} selected · ${apps.size} launchable apps", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!loaded) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        if (loaded && filtered.isEmpty()) item { Text(if (query.isBlank()) "No launchable apps found." else "No matching apps.", modifier = Modifier.padding(vertical = 24.dp)) }
        items(filtered, key = { it.packageName }) { app ->
            val rule = rules[app.packageName]?.takeIf { it.enabled }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !app.protected) { onEdit(app) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppMonogram(app.label)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            app.protected -> "Essential system app · always available"
                            rule != null -> if (rule.hours.mode == AllowedHoursMode.ANYTIME)
                                "${rule.limitMinutes} min per day · tap to edit"
                            else "${rule.hours.label} · ${rule.limitMinutes} Min./Tag"
                            else -> "No daily limit"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule != null,
                    onCheckedChange = { enabled -> if (enabled) onEdit(app) else onRemove(app) },
                    enabled = !app.protected,
                    modifier = Modifier.semantics { contentDescription = "Restrict ${app.label}" },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SettingsPage(
    state: LimitState,
    usageAccess: Boolean,
    accessibilityEnabled: Boolean,
    connected: Boolean,
    storageError: String?,
    padding: PaddingValues,
    onUsage: () -> Unit,
    onAccessibility: () -> Unit,
    onAppSettings: () -> Unit,
    onTheme: (String) -> Unit,
    onLimitReachedAction: (LimitReachedAction) -> Unit,
    serviceDiagnostics: String,
    onCopyDiagnostics: () -> Unit,
) {
    val settingsContext = LocalContext.current
    var notificationAllowed by remember { mutableStateOf(NotificationManagerCompat.from(settingsContext).areNotificationsEnabled()) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAllowed = NotificationManagerCompat.from(settingsContext).areNotificationsEnabled()
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("settings"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeading("Keep it simple.", "A little setup, then less screen time.") }
        if (storageError != null) item { Text(storageError, color = MaterialTheme.colorScheme.error) }
        item {
            Text("Background protection", style = MaterialTheme.typography.titleLarge)
            Text(if (ProtectionService.running) "Status service is running. Its notification shows whether monitoring is working."
                else "Open LIMIT after enabling accessibility to start the status service.")
            Text(if (notificationAllowed) "Protection notifications are allowed."
                else "Notifications are blocked. Background protection can run, but its warnings may be hidden.")
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                else onAppSettings()
            }) { Text("Review protection notifications") }
        }
        item { Text("Required permissions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item {
            PermissionCard("Usage access", usageAccess, "Reads which apps are in the foreground and for how long. Your usage stays on this device.", if (usageAccess) "Review access" else "Allow usage access", onUsage)
        }
        item {
            PermissionCard(
                "Accessibility service",
                accessibilityEnabled && connected,
                if (accessibilityEnabled && !connected) "Enabled in Settings, but the service is not connected. Re-enable LIMIT or reopen the app."
                else "Detects app changes and shows the blocking screen. LIMIT does not read screen text, capture screenshots, or control gestures.",
                if (accessibilityEnabled && connected) "Review service" else "Enable LIMIT service",
                onAccessibility,
            )
        }
        item {
            Text("If Android shows “Restricted setting”, open LIMIT’s App info, use the menu to allow restricted settings, then return to Accessibility. Only enable this for an APK you trust.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("When limit is reached", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(listOf(
            Triple(LimitReachedAction.OFFER_EXTENSION, "Offer extra time", "mode_offer_extension"),
            Triple(LimitReachedAction.RETURN_HOME, "Go home immediately", "mode_return_home"),
        )) { (action, label, tag) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag(tag)
                    .selectable(selected = state.limitReachedAction == action, role = Role.RadioButton,
                        onClick = { onLimitReachedAction(action) }).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.limitReachedAction == action, onClick = null)
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
        item { Text("Extra time uses the existing two daily exceptions of five minutes each.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(listOf("SYSTEM" to "Use device theme", "LIGHT" to "Light", "DARK" to "Dark")) { (value, label) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTheme(value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.theme == value, onClick = { onTheme(value) })
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item { Text("Xiaomi & background reliability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item {
            Text("On HyperOS, enable Background autostart and choose “No restrictions” under app battery settings if monitoring stops. Keeping LIMIT locked in Recents can help. Names vary by device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAppSettings) { Text("Open LIMIT app info") }
        }
        item { Text("You stay in control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { Text("LIMIT cannot enforce limits after force-stop, disabled permissions, or some system battery interventions. It is not an unbreakable lock. Split-screen and unusual app windows may affect Android’s usage reporting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Private by design", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        item { Text("Fully offline. No account, ads, analytics, or network access. Settings and allowances are stored only in LIMIT’s private app storage. Clearing app data or uninstalling removes them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text("Service diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Copy local errors, current permission status and Android process exit reasons. Available even when no local error was recorded. No app usage, screen contents or automatic upload.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (serviceDiagnostics.isNotBlank()) Text(serviceDiagnostics, style = MaterialTheme.typography.bodySmall,
                maxLines = 6, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onCopyDiagnostics, modifier = Modifier.testTag("copy_service_diagnostics")) { Text("Copy service diagnostics") }
        }
        item { Text("LIMIT 1.0.7", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) }
    }
}

@Composable
private fun PermissionCard(title: String, granted: Boolean, description: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (granted) "Ready" else "Setup needed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun LimitDialog(app: AppEntry, currentRule: AppRule, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    var value by remember(app.packageName) { mutableStateOf(currentRule.limitMinutes.toString()) }
    var mode by remember(app.packageName) { mutableStateOf(currentRule.hours.mode) }
    var from by remember(app.packageName) { mutableIntStateOf(currentRule.hours.fromMinute) }
    var until by remember(app.packageName) { mutableIntStateOf(currentRule.hours.untilMinute) }
    var selecting by remember { mutableStateOf<String?>(null) }
    val minutes = value.toIntOrNull()
    val valid = minutes != null && minutes in 0..1440 &&
        (mode != AllowedHoursMode.BETWEEN || from != until)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily limit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = value,
                    onValueChange = { candidate -> if (candidate.length <= 4 && candidate.all { it.isDigit() }) value = candidate },
                    label = { Text("Minutes per day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = minutes == null || minutes !in 0..1440,
                    supportingText = { Text(if (minutes != null && minutes in 0..1440) "0 blocks immediately. Resets at midnight." else "Enter a whole number from 0 to 1,440.") },
                    modifier = Modifier.fillMaxWidth().testTag("limit_input"),
                )
                Text("Erlaubte Nutzungszeit", style = MaterialTheme.typography.titleMedium)
                listOf(
                    AllowedHoursMode.ANYTIME to "Jederzeit",
                    AllowedHoursMode.FROM to "Ab Uhrzeit",
                    AllowedHoursMode.BETWEEN to "Von–bis",
                ).forEach { (option, title) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = mode == option, role = Role.RadioButton,
                            onClick = { mode = option }).testTag("hours_${option.name.lowercase()}")) {
                        RadioButton(selected = mode == option, onClick = null)
                        Text(title)
                    }
                }
                if (mode != AllowedHoursMode.ANYTIME) {
                    TextButton(onClick = { selecting = "from" }, modifier = Modifier.testTag("hours_from")) {
                        Text("Beginn: ${AllowedHours.formatMinute(from)}")
                    }
                    if (mode == AllowedHoursMode.BETWEEN) {
                        TextButton(onClick = { selecting = "until" }, modifier = Modifier.testTag("hours_until")) {
                            Text("Ende: ${AllowedHours.formatMinute(until)}")
                        }
                        if (from == until) {
                            Text("Beginn und Ende müssen verschieden sein.",
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Text("Changes apply now. Today’s usage and exceptions are kept.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(
            onClick = { if (valid) onSave(AppRule(minutes!!, hours = AllowedHours(mode, from, until))) },
            enabled = valid, modifier = Modifier.testTag("save_limit"),
        ) { Text("Save limit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    selecting?.let { field ->
        val selectedMinutes = if (field == "from") from else until
        val picker = rememberTimePickerState(
            initialHour = selectedMinutes / 60,
            initialMinute = selectedMinutes % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { selecting = null },
            title = { Text(if (field == "from") "Beginn auswählen" else "Ende auswählen") },
            text = { TimePicker(state = picker) },
            confirmButton = { TextButton(onClick = {
                val chosen = picker.hour * 60 + picker.minute
                if (field == "from") from = chosen else until = chosen
                selecting = null
            }, modifier = Modifier.testTag("save_hours")) { Text("OK") } },
            dismissButton = { TextButton(onClick = { selecting = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppMonogram(label: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(Locale.getDefault()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun NavigationGlyph(index: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 22f
        val stroke = Stroke(width = 1.8f * unit)
        when (index) {
            0 -> {
                drawCircle(color, radius = 9f * unit, style = stroke)
                drawLine(color, Offset(11f * unit, 5f * unit), Offset(11f * unit, 11f * unit), 1.8f * unit)
                drawLine(color, Offset(11f * unit, 11f * unit), Offset(15f * unit, 13f * unit), 1.8f * unit)
            }
            1 -> for (x in listOf(2f, 13f)) for (y in listOf(2f, 13f)) {
                drawRoundRect(color, Offset(x * unit, y * unit), Size(7f * unit, 7f * unit), CornerRadius(1.5f * unit), style = stroke)
            }
            else -> {
                for ((y, x) in listOf(5f to 8f, 11f to 15f, 17f to 8f)) {
                    drawLine(color, Offset(2f * unit, y * unit), Offset(20f * unit, y * unit), 1.6f * unit)
                    drawCircle(color, 2.4f * unit, Offset(x * unit, y * unit))
                }
            }
        }
    }
}

private fun duration(milliseconds: Long): String {
    if (milliseconds == Long.MAX_VALUE) return "Unlimited"
    val seconds = ((milliseconds.coerceAtLeast(0) + 999) / 1000)
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
