package dev.limit.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.limit.app.core.LimitEngine
import dev.limit.app.data.StateRepository
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LimitPersistenceTest {
    @Test fun committedSettingsAndRemainingCreditSurviveStoreRecreationOnAndroid() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "limit-persistence-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val date = LocalDate.now().toString()
        val repositories = mutableListOf<StateRepository>()
        try {
            val first = StateRepository(isolated).also { repositories += it }
            withTimeout(15_000) { first.ready.first { it } }
            first.setRule("example.persisted", 0)
            first.setTheme("DARK")
            first.grant("example.persisted")
            first.reconcile(date, mapOf("example.persisted" to 125_000L))
            val saved = first.state.value
            first.close()

            val reopened = StateRepository(isolated).also { repositories += it }
            withTimeout(15_000) { reopened.ready.first { it } }
            assertEquals(saved, reopened.state.value)
            val restored = LimitEngine.budget(reopened.state.value, date, "example.persisted")
            assertEquals(175_000L, restored.bonusRemainingMs)
            assertEquals(1, restored.grantsRemaining)
        } finally {
            repositories.forEach { it.close() }
            directory.deleteRecursively()
        }
    }
}
