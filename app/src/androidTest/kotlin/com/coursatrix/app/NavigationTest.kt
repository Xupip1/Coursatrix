package com.coursatrix.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fourModulesAreAccessible() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("让今天，从容一点")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("课表").performClick()
        compose.onNodeWithText("一周课表").assertIsDisplayed()
        compose.onNodeWithText("计划").performClick()
        compose.onNodeWithText("把想做的，慢慢完成").assertIsDisplayed()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("设置学期与作息").assertIsDisplayed()
        compose.onNodeWithText("日程").performClick()
        compose.onNodeWithText("让今天，从容一点").assertIsDisplayed()
    }
}
