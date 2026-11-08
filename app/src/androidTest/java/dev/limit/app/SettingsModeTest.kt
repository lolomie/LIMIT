package dev.limit.app

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.limit.app.core.LimitReachedAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsModeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val application get() = compose.activity.application as LimitApplication

    @Test fun selectedExhaustionBehaviorSurvivesActivityRecreation() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        val original = application.repository.state.value.limitReachedAction
        try {
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("mode_return_home"))
            compose.onNodeWithTag("mode_return_home").performClick()
            compose.waitUntil(15_000) {
                application.repository.state.value.limitReachedAction == LimitReachedAction.RETURN_HOME
            }
            compose.onNodeWithTag("mode_return_home").assertIsSelected()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(LimitReachedAction.RETURN_HOME, application.repository.state.value.limitReachedAction)
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("mode_return_home"))
            compose.onNodeWithTag("mode_return_home").assertIsSelected()
            compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("mode_offer_extension"))
            compose.onNodeWithTag("mode_offer_extension").performClick()
            compose.waitUntil(15_000) {
                application.repository.state.value.limitReachedAction == LimitReachedAction.OFFER_EXTENSION
            }
            compose.onNodeWithTag("mode_offer_extension").assertIsSelected()
        } finally {
            runBlocking { application.repository.setLimitReachedAction(original) }
        }
    }
}
