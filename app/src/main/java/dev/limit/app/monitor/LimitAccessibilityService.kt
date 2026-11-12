package dev.limit.app.monitor

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import dev.limit.app.core.RecoveryPolicy
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.FrameLayout
import dev.limit.app.LimitApplication
import dev.limit.app.core.AllowedHoursPolicy
import dev.limit.app.core.BlockReason
import dev.limit.app.core.LimitEngine
import dev.limit.app.core.LimitReachedAction
import dev.limit.app.core.BlockSessionPolicy
import dev.limit.app.core.CapsuleObservation
import dev.limit.app.core.UsageCapsuleModel
import dev.limit.app.data.AppCatalog
import dev.limit.app.data.ServiceDiagnostics
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Android binds this service only after explicit user activation in Settings. */
class LimitAccessibilityService : AccessibilityService() {
    companion object {
        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
        private val mutableFailure = MutableStateFlow<String?>(null)
        val failure: StateFlow<String?> = mutableFailure.asStateFlow()
        private var instance: LimitAccessibilityService? = null
        fun probe() {
            val service = instance ?: return
            if (!service.bound || service.destroyed) return
            val started = service.refreshStartedAt
            if (started != null && SystemClock.elapsedRealtime() - started > 90_000) {
                mutableConnected.value = false
                mutableFailure.value = "Monitoring is not responding. Re-enable the LIMIT accessibility service."
            } else if (service.retryExhausted && service.usableScreen()) service.requestRefresh(0)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error -> serviceFailure("background", error) })
    private val gate = Mutex()
    private val app get() = application as LimitApplication
    private val repository get() = app.repository
    private val tracker get() = app.tracker
    private var pending: Job? = null
    private var deadline: Job? = null
    private var periodic: Job? = null
    private var settling: Job? = null
    private var midnight: Job? = null
    private var timeBoundary: Job? = null
    private var refreshStartedAt: Long? = null
    private var overlay: View? = null
    private var blockedPackage: String? = null
    private var lastWindowPackage: String? = null
    private var exceptionButton: Button? = null
    private var exceptionLabel: TextView? = null
    private var errorLabel: TextView? = null
    private var blockTitle: TextView? = null
    private var blockDescription: TextView? = null
    private var blockedAppName: String = ""
    private var registered = false
    private var homes: Set<String> = setOf("com.miui.home", "com.android.launcher3", "com.google.android.apps.nexuslauncher")
    private var protectedPackages: Set<String> = setOf("android", "com.android.settings", "com.android.systemui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.phone",
        "com.android.server.telecom", "com.android.emergency", "com.miui.securitycenter")
    private var initialized = false
    private var bound = false
    private var destroyed = false
    private var observing = false
    private val recovery = RecoveryPolicy()
    private var retryExhausted = false
    private var retryJob: Job? = null
    private var sessionGeneration = 0L
    private var autoRedirectInFlight: String? = null
    private val capsule by lazy { UsageCapsuleController(this, scope) }
    private var capsuleFocusedPackage: String? = null
    private var capsuleEligiblePackages: Set<String> = emptySet()
    private var keyboardPackages: Set<String> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                hideCapsule()
                deadline?.cancel()
                periodic?.cancel()
                timeBoundary?.cancel()
                scope.launch { gate.withLock { clearOverlay() } }
            }
            if (intent?.action == Intent.ACTION_DATE_CHANGED || intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) hideCapsule()
            if (intent?.action != Intent.ACTION_SCREEN_OFF) resetRecovery() else requestRefresh(0)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = true
        instance = this
        recovery.reset()
        retryExhausted = false
        scope.launch { gate.withLock { initializeService() } }
    }

    private suspend fun initializeService() {
      try {
        val catalog = AppCatalog(this)
        homes = homes + catalog.homePackages()
        protectedPackages = protectedPackages + homes + packageName + catalog.protectedPackages()
        keyboardPackages = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList
                .mapTo(mutableSetOf()) { it.packageName }
        }.getOrElse { ServiceDiagnostics.record(this, "keyboard_catalog", it); emptySet() }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (!registered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
            registered = true
        }
        if (!observing) {
            observing = true
            scope.launch {
                repository.state.map { Triple(it.rules, it.theme, it.limitReachedAction) }
                    .distinctUntilChanged().collect { requestRefresh(0) }
            }
            scope.launch { repository.ready.collect { if (it) requestRefresh(0) } }
        }
        refreshCapsuleCatalog()
        if (!bound || destroyed) return
        initialized = true
        mutableFailure.value = null
        refresh()
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (error: Exception) { serviceFailure("connect", error) }
    }

    private fun serviceFailure(phase: String, error: Throwable) {
        if (error is CancellationException || destroyed) return
        ServiceDiagnostics.record(this, phase, error)
        mutableConnected.value = false
        mutableFailure.value = "Monitoring paused after a service error. Details are available in Settings."
        hideCapsule()
        deadline?.cancel()
        periodic?.cancel()
        midnight?.cancel()
        timeBoundary?.cancel()
        retryJob?.cancel()
        if (error is Error) {
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), error)
            return
        }
        if (!bound) return
        val wait = recovery.failureDelay(SystemClock.elapsedRealtime())
        if (wait == null) { retryExhausted = true; return }
        val retry = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            retryJob = null
            if (bound && !destroyed) gate.withLock {
                if (initialized) refresh() else initializeService()
            }
        }
        retryJob = retry
        retry.start()
    }

    private fun resetRecovery() {
        recovery.reset()
        retryExhausted = false
        retryJob?.cancel()
        retryJob = null
        requestRefresh(0)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
      try {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            if (event.className?.toString()?.endsWith("MainActivity") == true) {
                lastWindowPackage = pkg
                capsuleFocusedPackage = null
                hideCapsule()
                val expected = sessionGeneration
                scope.launch { gate.withLock { if (sessionGeneration == expected) clearOverlay() } }
                resetRecovery()
                requestRefresh(150)
            }
            return
        }
        if (pkg !in keyboardPackages) {
            val next = pkg.takeUnless { it in protectedPackages }
            if (next != capsuleFocusedPackage || next == null) hideCapsule()
            capsuleFocusedPackage = next
        }
        lastWindowPackage = pkg
        if (pkg in homes || pkg in protectedPackages) autoRedirectInFlight = null
        if (overlay != null && isCriticalSurface(pkg)) {
            val expected = sessionGeneration
            scope.launch { gate.withLock { if (sessionGeneration == expected) clearOverlay() } }
        }
        // Ignore keyboard/content changes; only window ownership is observed.
        requestRefresh(150)
        settling?.cancel()
        settling = scope.launch {
            // Android may publish UsageEvents after the accessibility window signal.
            // Two bounded retries cover that lag without idle polling.
            delay(600)
            requestRefresh(0)
            delay(1_400)
            requestRefresh(0)
        }
      } catch (error: Exception) { serviceFailure("window_event", error) }
    }

    private val criticalSurfaces = setOf(
        "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.phone", "com.android.server.telecom", "com.android.emergency", "com.miui.securitycenter"
    )

    private fun isCriticalSurface(pkg: String): Boolean = pkg in criticalSurfaces

    override fun onInterrupt() {
        hideCapsule()
        // Feedback interruption does not mean that Android disconnected this service.
        // Keep the user's extension choice intact across transient SystemUI feedback events.
        pending?.cancel()
        settling?.cancel()
        if (bound && initialized) requestRefresh(150)
    }

    private fun requestRefresh(waitMs: Long) {
        if (!bound || destroyed || retryJob?.isActive == true) return
        if (retryExhausted) {
            if (!recovery.allowEvent(SystemClock.elapsedRealtime())) return
            retryExhausted = false
        }
        pending?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMs)
            // New events may cancel a debounce, never an in-flight durable transaction.
            pending = null
            gate.withLock {
                if (bound && !destroyed && !retryExhausted && retryJob?.isActive != true) {
                    if (initialized) refresh() else initializeService()
                }
            }
        }
        pending = next
        next.start()
    }

    private fun usableScreen(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private suspend fun refresh() {
        if (!bound || destroyed) return
        deadline?.cancel()
        periodic?.cancel()
        midnight?.cancel()
        timeBoundary?.cancel()
      try {
        if (!repository.ready.value || !tracker.hasUsageAccess()) {
            mutableConnected.value = false
            hideCapsule()
            clearOverlay()
            return
        }
        if (!usableScreen()) {
            hideCapsule()
            clearOverlay()
            return
        }
            mutableFailure.value = null
            repository.checkpointBlock()
            refreshStartedAt = SystemClock.elapsedRealtime()
            val snapshot = try { tracker.snapshot() } finally { refreshStartedAt = null }
            if (!bound || destroyed) { hideCapsule(); clearOverlay(); return }
            if (!snapshot.available) {
                hideCapsule()
                clearOverlay()
                serviceFailure("usage_unavailable", IllegalStateException("Usage history is unavailable"))
                mutableFailure.value = snapshot.message ?: "Usage history is unavailable."
                return
            }
            if (snapshot.date != LocalDate.now().toString()) {
                hideCapsule()
                // IO may have crossed midnight after the snapshot's single clock capture.
                // Never reconcile yesterday or block with its expired allowance.
                requestRefresh(0)
                return
            }
            repository.reconcile(snapshot.date, snapshot.totals)
            val state = repository.state.value
            val blocked = blockedPackage
            if (blocked != null) {
                val budget = LimitEngine.budget(state, snapshot.date, blocked)
                val other = lastWindowPackage
                val confirmedNavigation = BlockSessionPolicy.shouldDismiss(sessionGeneration, sessionGeneration,
                    blocked, other, homes, protectedPackages, keyboardPackages, criticalSurfaces, snapshot.activePackages)
                val reason = AllowedHoursPolicy.reason(state.rules[blocked], budget)
                if (reason == BlockReason.NONE || confirmedNavigation) {
                    clearOverlay()
                } else if (reason == BlockReason.DAILY_BUDGET &&
                    state.limitReachedAction == LimitReachedAction.RETURN_HOME) {
                    clearOverlay()
                    returnHome(blocked)
                } else updateOverlay(reason, budget.grantsRemaining, blocked)
            }
            if (blockedPackage == null) {
                val candidates = snapshot.activePackages.filter {
                    it !in protectedPackages && state.rules[it]?.enabled == true &&
                        lastWindowPackage !in protectedPackages
                }.sortedBy { if (it == lastWindowPackage) 0 else 1 }
                val blockedCandidate = candidates.firstNotNullOfOrNull { pkg ->
                    val budget = LimitEngine.budget(state, snapshot.date, pkg)
                    val reason = AllowedHoursPolicy.reason(state.rules[pkg], budget)
                    if (reason == BlockReason.NONE) null else pkg to reason
                }
                if (blockedCandidate != null) {
                    val (pkg, reason) = blockedCandidate
                    if (reason == BlockReason.DAILY_BUDGET &&
                        state.limitReachedAction == LimitReachedAction.RETURN_HOME) returnHome(pkg)
                    else showOverlay(pkg, snapshot.date, reason)
                } else if (candidates.isNotEmpty()) {
                    val remaining = candidates.minOf {
                        LimitEngine.budget(state, snapshot.date, it).remainingMs
                    }
                    deadline = scope.launch {
                        delay(remaining.coerceAtLeast(100))
                        requestRefresh(0)
                    }
                }
                if (candidates.isNotEmpty() || lastWindowPackage?.let {
                        state.rules[it]?.enabled
                    } == true) periodic = scope.launch {
                    delay(15_000)
                    requestRefresh(0)
                }
            } else {
                // Sparse checkpoint also limits a crashed overlay's exclusion interval.
                periodic = scope.launch { delay(15_000); requestRefresh(0) }
            }
            // Presentation has no authority over rules, grants or measured usage.
            if (updateCapsule(snapshot, snapshot.observedElapsedMs) && periodic?.isActive != true) {
                periodic = scope.launch { delay(15_000); requestRefresh(0) }
            }
            val clock = Clock.systemDefaultZone()
            val now = clock.millis()
            val nextBoundary = state.rules.values.asSequence()
                .filter { it.enabled }
                .mapNotNull { AllowedHoursPolicy.nextTransition(it.hours, clock) }
                .minOrNull()
            if (nextBoundary != null) {
                timeBoundary = scope.launch {
                    delay((nextBoundary.toEpochMilli() - now).coerceAtLeast(1))
                    requestRefresh(0)
                }
            }
            val nextDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            midnight = scope.launch { delay((nextDay - now).coerceAtLeast(1)); requestRefresh(0) }
            mutableConnected.value = bound && !destroyed
            recovery.reset()
            retryExhausted = false
            retryJob?.cancel()
            retryJob = null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            serviceFailure("refresh", error)
            clearOverlay()
        }
    }

    private fun returnHome(pkg: String) {
        hideCapsule()
        if (autoRedirectInFlight == pkg) return
        autoRedirectInFlight = pkg
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            autoRedirectInFlight = null
            throw IllegalStateException("Android could not navigate Home")
        }
    }

    private suspend fun showOverlay(pkg: String, date: String, reason: BlockReason) {
        protectedPackages = protectedPackages + AppCatalog(this).protectedPackages()
        if (!usableScreen() || pkg in protectedPackages) return
        hideCapsule()
        repository.beginBlock(pkg)
        blockedPackage = pkg
        val session = ++sessionGeneration
        // Move the exhausted activity out of RESUMED, so overlay dwell never uses credit.
        performGlobalAction(GLOBAL_ACTION_HOME)
        val dark = when (repository.state.value.theme) {
            "DARK" -> true
            "LIGHT" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val ink = if (dark) Color.rgb(241, 241, 235) else Color.rgb(25, 29, 25)
        val background = if (dark) Color.rgb(18, 22, 19) else Color.rgb(246, 247, 240)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(40), dp(32), dp(40))
            setBackgroundColor(background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(ink)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(12))
            layout.addView(this)
        }
        label("LIMIT", 14f, true)
        val appName = runCatching { packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        blockedAppName = appName
        blockTitle = label("Time to step away.", 32f, true)
        blockDescription = label("You've reached today's limit for $appName.", 18f)
        exceptionLabel = label("", 15f)
        exceptionButton = Button(this).apply {
            text = "Use 5 minutes"
            isAllCaps = false
            contentDescription = "Use one exception for five more minutes"
            layout.addView(this, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(20) })
            setOnClickListener {
                isEnabled = false
                scope.launch { gate.withLock { redeem(pkg, session) } }
            }
        }
        Button(this).apply {
            text = "Go home"
            isAllCaps = false
            layout.addView(this, LinearLayout.LayoutParams(-1, dp(56)))
            setOnClickListener {
                scope.launch { gate.withLock {
                    if (sessionGeneration == session) { clearOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) }
                } }
            }
        }
        errorLabel = label("", 14f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.OPAQUE
        )
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(layout, FrameLayout.LayoutParams(-1, -2))
        }
        try {
            getSystemService(WindowManager::class.java).addView(scroll, params)
            overlay = scroll
            updateOverlay(reason, LimitEngine.budget(repository.state.value, date, pkg).grantsRemaining, pkg)
        } catch (error: Exception) {
            clearOverlay()
            throw error
        }
    }

    private fun updateOverlay(reason: BlockReason, grants: Int, pkg: String) {
        val outside = reason == BlockReason.OUTSIDE_HOURS
        blockTitle?.text = if (outside) "Erlaubte Nutzungszeit" else "Time to step away."
        blockDescription?.text = if (outside) {
            val clock = Clock.systemDefaultZone()
            val opening = repository.state.value.rules[pkg]?.hours
                ?.let { AllowedHoursPolicy.nextOpening(it, clock) }
            if (opening == null) "$blockedAppName ist derzeit nicht verfügbar."
            else {
                val dateTime = opening.atZone(clock.zone)
                val prefix = if (dateTime.toLocalDate() == LocalDate.now(clock)) "" else "morgen "
                "$blockedAppName ist ${prefix}ab ${dateTime.toLocalTime()} Uhr wieder verfügbar."
            }
        } else "You've reached today's limit for $blockedAppName."
        exceptionLabel?.visibility = if (outside) View.GONE else View.VISIBLE
        exceptionButton?.visibility = if (outside) View.GONE else View.VISIBLE
        if (!outside) {
            exceptionLabel?.text = if (grants == 0) "No exceptions left today. Resets at midnight."
                else "$grants of 2 exceptions left today. Each gives 5 minutes."
            exceptionButton?.isEnabled = grants > 0
        }
    }

    private suspend fun redeem(pkg: String, session: Long) {
        try {
            check(sessionGeneration == session && pkg == blockedPackage && usableScreen() && tracker.hasUsageAccess())
            check(repository.state.value.limitReachedAction == LimitReachedAction.OFFER_EXTENSION)
            val snapshot = tracker.snapshot()
            check(snapshot.available)
            repository.reconcile(snapshot.date, snapshot.totals)
            check(bound && !destroyed && sessionGeneration == session && pkg == blockedPackage)
            check(repository.state.value.rules[pkg]?.let { AllowedHoursPolicy.isAllowed(it.hours) } == true) {
                "The allowed time window has closed."
            }
            val launchIntent = checkNotNull(packageManager.getLaunchIntentForPackage(pkg)) {
                "This app is no longer available."
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            repository.grant(pkg, snapshot.date) // Commit before exposing the app; reject midnight races.
            if (!AllowedHoursPolicy.isAllowed(repository.state.value.rules.getValue(pkg).hours)) {
                updateOverlay(BlockReason.OUTSIDE_HOURS,
                    LimitEngine.budget(repository.state.value, snapshot.date, pkg).grantsRemaining, pkg)
                requestRefresh(0)
                return
            }
            clearOverlay()
            startActivity(launchIntent)
            requestRefresh(350)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            errorLabel?.text = "Could not use an exception. Check permissions and try again."
            val today = LocalDate.now().toString()
            val budget = LimitEngine.budget(repository.state.value, today, pkg)
            val reason = AllowedHoursPolicy.reason(repository.state.value.rules[pkg], budget)
            updateOverlay(reason, budget.grantsRemaining, pkg)
        }
    }

    private suspend fun clearOverlay() {
        sessionGeneration++
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        overlay = null
        blockedPackage = null
        exceptionButton = null
        exceptionLabel = null
        errorLabel = null
        blockTitle = null
        blockDescription = null
        blockedAppName = ""
        try { repository.endBlock() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableFailure.value = "Blocking state could not be saved. Monitoring is paused."
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hideCapsule() { runCatching { capsule.hide() } }

    private suspend fun refreshCapsuleCatalog() {
        try {
            capsuleEligiblePackages = withContext(Dispatchers.IO) {
                AppCatalog(this@LimitAccessibilityService).load()
                    .filter { !it.protected && it.packageName !in keyboardPackages }
                    .mapTo(mutableSetOf()) { it.packageName }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { hideCapsule() }
    }

    private suspend fun updateCapsule(snapshot: TrackedUsage, observedElapsed: Long): Boolean {
        return try {
            if (autoRedirectInFlight != null) { hideCapsule(); return false }
            val focused = capsuleFocusedPackage
            if (focused != null && focused !in capsuleEligiblePackages) refreshCapsuleCatalog()
            val selected = UsageCapsuleModel.selectPackage(focused, snapshot.activePackages,
                capsuleEligiblePackages, usableScreen(), snapshot.available, blockedPackage != null)
            if (selected == null) { hideCapsule(); return false }
            val observation = CapsuleObservation(selected, snapshot.date,
                snapshot.totals[selected] ?: 0L, observedElapsed)
            capsule.show(observation) {
                capsuleFocusedPackage == selected && blockedPackage == null &&
                    bound && !destroyed && repository.ready.value && tracker.hasUsageAccess() && usableScreen()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { hideCapsule(); false }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardPackages = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList
                .mapTo(mutableSetOf()) { it.packageName }
        }.getOrDefault(keyboardPackages)
        runCatching { capsule.refreshPlacement() }
        requestRefresh(0)
    }

    override fun onDestroy() {
        destroyed = true
        if (instance === this) instance = null
        bound = false
        hideCapsule()
        timeBoundary?.cancel()
        mutableConnected.value = false
        if (registered) runCatching { unregisterReceiver(receiver) }
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        repository.finishBlockAsync()
        // Last durable checkpoint bounds any unclosed exclusion after process termination.
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        bound = false
        mutableConnected.value = false
        pending?.cancel()
        retryJob?.cancel()
        periodic?.cancel()
        deadline?.cancel()
        midnight?.cancel()
        timeBoundary?.cancel()
        hideCapsule()
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) } }
        overlay = null
        blockedPackage = null
        sessionGeneration++
        repository.finishBlockAsync()
        return super.onUnbind(intent)
    }
}
