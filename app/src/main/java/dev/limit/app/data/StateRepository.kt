package dev.limit.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.limit.app.core.AppRule
import dev.limit.app.core.AppUsage
import dev.limit.app.core.DayLedger
import dev.limit.app.core.LimitEngine
import dev.limit.app.core.LimitReachedAction
import dev.limit.app.core.LimitState
import dev.limit.app.core.UsageExclusion
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A single application-scoped transactional store. Failed reads never reset allowances. */
class StateRepository(context: Context) {
    private val protectedPackages by lazy { AppCatalog(context.applicationContext).protectedPackages() }
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) {
        context.applicationContext.preferencesDataStoreFile("limit")
    }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LimitState())
    private val mutableReady = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)
    val state: StateFlow<LimitState> = mutableState.asStateFlow()
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val stateKey = stringPreferencesKey("state_v1")
    private val blocksKey = stringPreferencesKey("blocked_intervals_v1")
    private var ongoingBlock: UsageExclusion? = null

    init {
        scope.launch {
            try {
                // This repository is the sole writer. One initialization plus mutex-owned
                // committed publications prevents a delayed collector regressing state.
                mutex.withLock {
                    val preferences = store.data.first()
                    mutableState.value = decode(preferences[stateKey])
                    mutableReady.value = true
                    mutableError.value = null
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableReady.value = false
                mutableError.value = "Local settings could not be read. Monitoring is paused."
            }
        }
    }

    suspend fun reconcile(date: String, totals: Map<String, Long>) = mutate {
        LimitEngine.reconcile(it, date, totals)
    }

    suspend fun setRule(packageName: String, minutes: Int?) = mutate {
        require(minutes == null || packageName !in protectedPackages) { "Essential system apps cannot be restricted." }
        LimitEngine.setRule(it, LocalDate.now().toString(), packageName, minutes?.let { value -> AppRule(value) })
    }

    suspend fun setTheme(theme: String) {
        require(theme in setOf("SYSTEM", "LIGHT", "DARK"))
        mutate { it.copy(theme = theme) }
    }

    suspend fun setLimitReachedAction(action: LimitReachedAction) = mutate {
        it.copy(limitReachedAction = action)
    }

    suspend fun grant(packageName: String, expectedDate: String = LocalDate.now().toString()) = mutate {
        check(expectedDate == LocalDate.now().toString()) { "The day changed. Refresh before granting." }
        check(it.limitReachedAction == LimitReachedAction.OFFER_EXTENSION) { "Extra time is disabled in Settings." }
        LimitEngine.grant(it, expectedDate, packageName)
    }

    private suspend fun mutate(transform: (LimitState) -> LimitState) {
        mutex.withLock {
            try {
                val committed = store.edit { preferences ->
                    val today = LocalDate.now().minusDays(7).toString()
                    val next = transform(decode(preferences[stateKey])).let { state ->
                        state.copy(days = state.days.mapValues { (date, ledger) ->
                            if (date < today) ledger.copy(apps = emptyMap()) else ledger
                        })
                    }
                    preferences[stateKey] = encode(next)
                }
                mutableState.value = decode(committed[stateKey])
                mutableReady.value = true
                mutableError.value = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: IllegalArgumentException) { throw error
            } catch (error: IllegalStateException) { throw error
            } catch (error: Exception) {
                mutableError.value = "Changes could not be saved. Please try again."
                throw error
            }
        }
    }

    /** Test/application lifecycle hook. Await shutdown before opening the same file again. */
    suspend fun close() { scopeJob.cancelAndJoin() }

    /** Only a live process treats an interval as open. A crash leaves its last durable end. */
    suspend fun beginBlock(packageName: String, now: Long = System.currentTimeMillis()) {
        mutex.withLock {
            ongoingBlock = UsageExclusion(packageName, now, Long.MAX_VALUE)
            writeBlocks(now)
        }
    }

    suspend fun checkpointBlock(now: Long = System.currentTimeMillis()) {
        mutex.withLock { if (ongoingBlock != null) writeBlocks(now) }
    }

    suspend fun endBlock(now: Long = System.currentTimeMillis()) {
        mutex.withLock {
            if (ongoingBlock != null) writeBlocks(now)
            ongoingBlock = null
        }
    }

    fun finishBlockAsync() {
        scope.launch { runCatching { endBlock() } }
    }

    suspend fun exclusions(now: Long): List<UsageExclusion> = mutex.withLock {
        val saved = readBlocks(store.data.first()[blocksKey]).toMutableList()
        ongoingBlock?.let { live ->
            saved.removeAll { it.packageName == live.packageName && it.startMs == live.startMs }
            saved += live.copy(endMs = now + 1)
        }
        saved
    }

    private suspend fun writeBlocks(now: Long) {
        val live = ongoingBlock ?: return
        store.edit { preferences ->
            val keep = readBlocks(preferences[blocksKey]).filter {
                it.endMs >= now - 3L * 86_400_000 &&
                    !(it.packageName == live.packageName && it.startMs == live.startMs)
            }.toMutableList()
            keep += live.copy(endMs = maxOf(now, live.startMs))
            preferences[blocksKey] = JSONArray().apply {
                keep.forEach { interval -> put(JSONObject().put("package", interval.packageName)
                    .put("start", interval.startMs).put("end", interval.endMs)) }
            }.toString()
        }
    }

    private fun readBlocks(value: String?): List<UsageExclusion> {
        val array = JSONArray(value ?: "[]")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            UsageExclusion(item.getString("package"), item.getLong("start"), item.getLong("end"))
        }
    }

    private fun encode(state: LimitState): String = JSONObject().apply {
        put("version", 1)
        put("theme", state.theme)
        put("limitReachedAction", state.limitReachedAction.name)
        put("rules", JSONObject().apply {
            state.rules.forEach { (pkg, rule) -> put(pkg, JSONObject()
                .put("minutes", rule.limitMinutes).put("enabled", rule.enabled)) }
        })
        put("days", JSONObject().apply {
            state.days.forEach { (date, day) -> put(date, JSONObject()
                .put("grants", day.grantsUsed).put("apps", JSONObject().apply {
                    day.apps.forEach { (pkg, usage) -> put(pkg, JSONObject()
                        .put("used", usage.usedMs).put("bonus", usage.bonusRemainingMs)) }
                })) }
        })
    }.toString()

    private fun decode(value: String?): LimitState {
        if (value == null) return LimitState()
        val json = JSONObject(value)
        require(json.getInt("version") == 1) { "Unknown settings version" }
        val rulesJson = json.getJSONObject("rules")
        val rules = rulesJson.keys().asSequence().associateWith { pkg ->
            rulesJson.getJSONObject(pkg).let { AppRule(it.getInt("minutes"), it.getBoolean("enabled")) }
        }
        val daysJson = json.getJSONObject("days")
        val days = daysJson.keys().asSequence().associateWith { date ->
            val day = daysJson.getJSONObject(date)
            val appsJson = day.getJSONObject("apps")
            DayLedger(date, day.getInt("grants"), appsJson.keys().asSequence().associateWith { pkg ->
                appsJson.getJSONObject(pkg).let { AppUsage(it.getLong("used"), it.getLong("bonus")) }
            })
        }
        val action = LimitReachedAction.entries.firstOrNull {
            it.name == json.optString("limitReachedAction", LimitReachedAction.OFFER_EXTENSION.name)
        } ?: LimitReachedAction.OFFER_EXTENSION
        return LimitState(rules, days, json.getString("theme"), action)
    }
}
