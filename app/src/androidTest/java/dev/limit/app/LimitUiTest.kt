package dev.limit.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.limit.app.core.AllowedHours
import dev.limit.app.core.AllowedHoursMode
import dev.limit.app.data.AppCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LimitUiTest {
    @Test fun diagnosticsCanBeCopiedWithoutAnyRecordedFailure() {
        awaitDashboard()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasText("Copy service diagnostics"))
        // Android 10+ only lets the focused foreground app read the clipboard.
        compose.waitUntil(15_000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithText("Copy service diagnostics").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Service diagnostics copied.").fetchSemanticsNodes().isNotEmpty()
        }
        var copied = ""
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            copied.contains("LIMIT diagnostics")
        }
        org.junit.Assert.assertTrue(copied.contains("LIMIT diagnostics"))
        org.junit.Assert.assertTrue(copied.contains("Service connected:"))
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val application get() = compose.activity.application as LimitApplication

    private fun awaitDashboard() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun launchAndNavigateAllThreeScreens() {
        awaitDashboard()
        compose.onNodeWithTag("nav_apps").performClick()
        compose.onNodeWithTag("apps").assertIsDisplayed()
        compose.onNodeWithTag("app_search").assertIsDisplayed()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        compose.onNodeWithTag("nav_today").performClick()
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun chooseLaunchableAppAndSaveImmediateLimitThroughUi() {
        awaitDashboard()
        val app = AppCatalog(compose.activity).load().firstOrNull { !it.protected }
        assumeTrue("An ordinary launchable app must be installed", app != null)
        app!!
        val original = application.repository.state.value.rules[app.packageName]
        try {
            compose.onNodeWithTag("nav_apps").performClick()
            compose.onNodeWithTag("app_search").performTextReplacement(app.packageName)
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("Restrict ${app.label}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Restrict ${app.label}").performClick()
            // An already enabled switch removes the rule. Reopen the editor if needed.
            if (original?.enabled == true) {
                compose.waitUntil(15_000) { application.repository.state.value.rules[app.packageName] == null }
                compose.onNodeWithContentDescription("Restrict ${app.label}").performClick()
            }
            compose.onNodeWithTag("limit_input").performTextReplacement("0")
            compose.onNodeWithTag("save_limit").performClick()
            compose.waitUntil(15_000) {
                application.repository.state.value.rules[app.packageName]?.limitMinutes == 0
            }
            compose.onNodeWithText("0 min per day · tap to edit").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            assertEquals(0, application.repository.state.value.rules[app.packageName]?.limitMinutes)
        } finally {
            runBlocking { application.repository.setRule(app.packageName, original?.limitMinutes, original?.hours) }
        }
    }

    @Test fun chooseFromHoursInEditorAndKeepRuleAfterActivityRecreation() {
        awaitDashboard()
        val app = AppCatalog(compose.activity).load().firstOrNull { !it.protected }
        assumeTrue("An ordinary launchable app must be installed", app != null)
        app!!
        val original = application.repository.state.value.rules[app.packageName]
        try {
            compose.onNodeWithTag("nav_apps").performClick()
            compose.onNodeWithTag("app_search").performTextReplacement(app.packageName)
            compose.waitUntil(30_000) {
                compose.onAllNodesWithContentDescription("Restrict ${app.label}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            if (original?.enabled == true) {
                compose.onNodeWithContentDescription("Restrict ${app.label}").performClick()
                compose.waitUntil(15_000) { application.repository.state.value.rules[app.packageName] == null }
            }
            compose.onNodeWithContentDescription("Restrict ${app.label}").performClick()
            compose.onNodeWithTag("hours_from").performScrollTo().performClick()
            compose.onNodeWithTag("save_limit").performClick()
            compose.waitUntil(15_000) {
                application.repository.state.value.rules[app.packageName]?.hours?.mode ==
                    AllowedHoursMode.FROM
            }
            compose.activityRule.scenario.recreate()
            assertEquals(AllowedHoursMode.FROM,
                application.repository.state.value.rules[app.packageName]?.hours?.mode)
        } finally {
            runBlocking { application.repository.setRule(app.packageName, original?.limitMinutes, original?.hours) }
        }
    }

    @Test fun themeSelectionSurvivesActivityRecreation() {
        awaitDashboard()
        val original = application.repository.state.value.theme
        try {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings").performScrollToNode(hasText("Light"))
            compose.onNodeWithText("Light", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { application.repository.state.value.theme == "LIGHT" }
            compose.onNodeWithTag("settings").performScrollToNode(hasText("Dark"))
            compose.onNodeWithText("Dark", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { application.repository.state.value.theme == "DARK" }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("DARK", application.repository.state.value.theme)
            compose.onNodeWithTag("settings").assertIsDisplayed()
        } finally {
            runBlocking { application.repository.setTheme(original) }
        }
    }
}
