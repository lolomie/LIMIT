package dev.limit.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import dev.limit.app.core.BudgetPhase
import dev.limit.app.core.LimitEngine
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StateRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val repositories = mutableListOf<StateRepository>()
    private val today get() = LocalDate.now().toString()
    private val alpha = "example.alpha"
    private val beta = "example.beta"
    private val storageContext: Context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder.root
        }
    }

    private suspend fun open(): StateRepository = StateRepository(storageContext).also {
        repositories += it
        withTimeout(10_000) { it.ready.first { ready -> ready } }
    }

    @After fun closeRepositories() = runBlocking {
        repositories.forEach { it.close() }
    }

    @Test fun rulesThemeUsageAndPartialExceptionSurviveRepositoryRecreation() = runBlocking {
        val first = open()
        first.setRule(alpha, 0)
        first.setRule(beta, 45)
        first.setTheme("DARK")
        first.grant(alpha)
        first.reconcile(today, mapOf(alpha to 120_000L, beta to 30_000L))
        val committed = first.state.value
        first.close()

        val reopened = open()
        assertEquals(committed, reopened.state.value)
        assertEquals(45, reopened.state.value.rules[beta]?.limitMinutes)
        assertEquals("DARK", reopened.state.value.theme)
        val budget = LimitEngine.budget(reopened.state.value, today, alpha)
        assertEquals(120_000L, budget.usedMs)
        assertEquals(180_000L, budget.bonusRemainingMs)
        assertEquals(1, budget.grantsRemaining)
        assertEquals(BudgetPhase.EXCEPTION_ACTIVE, budget.phase)
    }

    @Test fun concurrentDuplicateGrantRequestsCommitExactlyOneGrant() = runBlocking {
        val repository = open()
        repository.setRule(alpha, 0)
        val attempts = coroutineScope {
            (1..20).map {
                async(Dispatchers.Default) {
                    try { repository.grant(alpha); true }
                    catch (_: IllegalStateException) { false }
                }
            }.awaitAll()
        }
        assertEquals(1, attempts.count { it })
        assertEquals(1, repository.state.value.days[today]?.grantsUsed)
        assertEquals(300_000L, LimitEngine.budget(repository.state.value, today, alpha).bonusRemainingMs)
    }

    @Test fun staleDateGrantCannotSpendTheNewDaysAllowance() = runBlocking {
        val repository = open()
        repository.setRule(alpha, 0)
        var rejected = false
        try { repository.grant(alpha, LocalDate.now().minusDays(1).toString()) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue("A click from yesterday's blocker must refresh before granting", rejected)
        assertEquals(2, LimitEngine.budget(repository.state.value, today, alpha).grantsRemaining)
    }

    @Test fun concurrentDifferentAppsCannotExceedTwoGlobalGrants() = runBlocking {
        val repository = open()
        val packages = listOf(alpha, beta, "example.third", "example.fourth")
        packages.forEach { repository.setRule(it, 0) }
        val successes = coroutineScope {
            packages.map { pkg -> async(Dispatchers.Default) {
                try { repository.grant(pkg); true }
                catch (_: IllegalStateException) { false }
            } }.awaitAll()
        }
        assertEquals(2, successes.count { it })
        assertEquals(2, repository.state.value.days[today]?.grantsUsed)
        assertEquals(2, repository.state.value.days[today]?.apps?.values?.count { it.bonusRemainingMs == 300_000L })
        repository.close()
        assertEquals(2, open().state.value.days[today]?.grantsUsed)
    }

    @Test fun durableBlockerIntervalsSurviveRestartWithoutStayingOpenForever() = runBlocking {
        val repository = open()
        repository.beginBlock(alpha, 1_000)
        repository.checkpointBlock(6_000)
        assertTrue(repository.exclusions(9_000).any { it.startMs == 1_000L && it.endMs > 9_000L })
        repository.close()

        val reopened = open()
        val interval = reopened.exclusions(20_000).single { it.packageName == alpha }
        assertEquals(1_000L, interval.startMs)
        assertEquals(6_000L, interval.endMs)
    }

    @Test fun completedBlockerIntervalsPersistTheirActualEnd() = runBlocking {
        val repository = open()
        repository.beginBlock(alpha, 1_000)
        repository.endBlock(6_000)
        val interval = repository.exclusions(20_000).single()
        assertEquals(6_000L, interval.endMs)
        repository.close()
        assertEquals(interval, open().exclusions(20_000).single())
    }

    @Test fun unsupportedStoredStatePausesMonitoringInsteadOfResettingAllowances() = runBlocking {
        val writerJob = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(writerJob + Dispatchers.IO)) {
            storageContext.preferencesDataStoreFile("limit")
        }
        store.edit { it[stringPreferencesKey("state_v1")] = """{"version":99}""" }
        writerJob.cancelAndJoin()

        val repository = StateRepository(storageContext).also { repositories += it }
        val message = withTimeout(10_000) { repository.error.first { it != null } }
        assertNotNull(message)
        assertFalse(repository.ready.value)
        var rejected = false
        try { repository.grant(alpha) } catch (_: Exception) { rejected = true }
        assertTrue("Unreadable state must not create new daily exceptions", rejected)
    }
}
