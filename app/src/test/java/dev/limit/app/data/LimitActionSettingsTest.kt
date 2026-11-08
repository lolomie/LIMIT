package dev.limit.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import dev.limit.app.core.LimitReachedAction
import dev.limit.app.core.LimitEngine
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LimitActionSettingsTest {
    @get:Rule val folder = TemporaryFolder()
    private val repositories = mutableListOf<StateRepository>()
    private val context: Context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder.root
        }
    }

    private suspend fun open(): StateRepository = StateRepository(context).also {
        repositories += it
        withTimeout(10_000) { it.ready.first { ready -> ready } }
    }

    @After fun closeRepositories() = runBlocking { repositories.forEach { it.close() } }

    @Test fun newInstallOffersExtensions() = runBlocking {
        assertEquals(LimitReachedAction.OFFER_EXTENSION, open().state.value.limitReachedAction)
    }

    @Test fun autoModeRejectsGrantWithoutSpendingExceptions() = runBlocking {
        val repository = open()
        repository.setRule("example.alpha", 0)
        repository.setLimitReachedAction(LimitReachedAction.RETURN_HOME)
        var rejected = false
        try { repository.grant("example.alpha") }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue("A stale blocker must not spend an exception after the policy changes", rejected)
        assertEquals(2, LimitEngine.budget(repository.state.value, LocalDate.now().toString(), "example.alpha").grantsRemaining)
        assertEquals(0L, LimitEngine.budget(repository.state.value, LocalDate.now().toString(), "example.alpha").bonusRemainingMs)
    }

    @Test fun returningToOfferModePreservesBothSlotsAndAllowsGrant() = runBlocking {
        val repository = open()
        repository.setRule("example.alpha", 0)
        repository.setLimitReachedAction(LimitReachedAction.RETURN_HOME)
        repository.setLimitReachedAction(LimitReachedAction.OFFER_EXTENSION)
        assertEquals(2, LimitEngine.budget(repository.state.value, LocalDate.now().toString(), "example.alpha").grantsRemaining)
        repository.grant("example.alpha")
        assertEquals(1, LimitEngine.budget(repository.state.value, LocalDate.now().toString(), "example.alpha").grantsRemaining)
        assertEquals(300_000L, LimitEngine.budget(repository.state.value, LocalDate.now().toString(), "example.alpha").bonusRemainingMs)
    }

    @Test fun selectedActionSurvivesRecreationWithoutChangingLimitsUsageOrExceptions() = runBlocking {
        val first = open()
        first.setRule("example.alpha", 0)
        first.setTheme("DARK")
        first.grant("example.alpha")
        first.reconcile(LocalDate.now().toString(), mapOf("example.alpha" to 120_000L))
        val before = first.state.value
        first.setLimitReachedAction(LimitReachedAction.RETURN_HOME)
        first.close()
        val reopened = open()
        assertEquals(before.copy(limitReachedAction = LimitReachedAction.RETURN_HOME), reopened.state.value)
        reopened.setLimitReachedAction(LimitReachedAction.OFFER_EXTENSION)
        assertEquals(before, reopened.state.value)
    }

    @Test fun schemaOneWithoutActionKeepsItsExistingData() = runBlocking {
        writeLegacy("")
        val state = open().state.value
        assertEquals(LimitReachedAction.OFFER_EXTENSION, state.limitReachedAction)
        assertEquals("LIGHT", state.theme)
        assertEquals(30, state.rules["example.alpha"]?.limitMinutes)
        assertEquals(2, state.days[LocalDate.now().toString()]?.grantsUsed)
        assertEquals(42L, state.days[LocalDate.now().toString()]?.apps?.get("example.alpha")?.usedMs)
    }

    @Test fun unknownActionSafelyDefaultsWithoutResettingExceptions() = runBlocking {
        writeLegacy(",\"limitReachedAction\":\"FUTURE_MODE\"")
        val state = open().state.value
        assertEquals(LimitReachedAction.OFFER_EXTENSION, state.limitReachedAction)
        assertEquals(2, state.days[LocalDate.now().toString()]?.grantsUsed)
    }

    private suspend fun writeLegacy(actionField: String) {
        val writerJob = SupervisorJob()
        val writer = PreferenceDataStoreFactory.create(scope = CoroutineScope(writerJob + Dispatchers.IO)) {
            context.preferencesDataStoreFile("limit")
        }
        writer.edit {
            it[stringPreferencesKey("state_v1")] = """{"version":1,"theme":"LIGHT","rules":{"example.alpha":{"minutes":30,"enabled":true}},"days":{"${LocalDate.now()}":{"grants":2,"apps":{"example.alpha":{"used":42,"bonus":0}}}}$actionField}"""
        }
        writerJob.cancelAndJoin()
    }
}
