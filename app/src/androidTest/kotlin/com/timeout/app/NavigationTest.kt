package com.timeout.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timeout.app.data.AppCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun requireEmptyTestInstallation() {
        assumeTrue(runBlocking { (compose.activity.application as TimeOutApplication).container.repository.snapshot().rules.isEmpty() })
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Block now").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun bedtimeCanBeConfiguredWithoutStartingADemonstrationTimer() {
        compose.onNodeWithText("Bedtime").performScrollTo().performClick()
        compose.onNodeWithText("Set a routine").assertExists()
        compose.onNodeWithContentDescription("Ends next day for window 1").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Block now").assertExists()
    }

    @Test fun appChoiceSurvivesReturningFromThePicker() {
        val app = AppCatalog(compose.activity).load().first()
        compose.onNodeWithText("Block now").performClick()
        compose.onNodeWithText("Choose apps").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(app.label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.label).performClick()
        compose.onNodeWithText("Use these apps").performClick()
        compose.onNodeWithText("1 app selected · tap to review").assertExists()
        compose.onNodeWithText("Apps", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(app.label).assertIsOn()
    }
}
