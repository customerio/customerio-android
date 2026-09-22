package io.customer.android.sample.kotlin_compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.android.sample.kotlin_compose.ui.inline.compose.CONDITIONAL_INLINE_ELEMENT_ID
import io.customer.android.sample.kotlin_compose.ui.inline.compose.INLINE_AVAILABILITY_LIST_TAG
import io.customer.android.sample.kotlin_compose.ui.inline.compose.INLINE_AVAILABILITY_STATUS_TAG
import io.customer.android.sample.kotlin_compose.ui.inline.compose.INLINE_BOTTOM_ITEM_TAG
import io.customer.android.sample.kotlin_compose.ui.inline.compose.INLINE_CENTER_ITEM_TAG
import io.customer.android.sample.kotlin_compose.ui.inline.compose.KotlinComposeInlineContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InlineAvailabilityInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun availabilityItem_whenAvailabilityChanges_entersAndLeavesLazyList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isAvailable = mutableStateOf(false)

        composeTestRule.setContent {
            KotlinComposeInlineContent(
                context = context,
                isCenterMessageAvailable = isAvailable.value
            )
        }

        composeTestRule.onNodeWithTag(INLINE_AVAILABILITY_STATUS_TAG)
            .assertTextContains("$CONDITIONAL_INLINE_ELEMENT_ID: unavailable")
        assertNodeCount(INLINE_CENTER_ITEM_TAG, expectedCount = 0)

        composeTestRule.runOnUiThread { isAvailable.value = true }
        composeTestRule.onNodeWithTag(INLINE_AVAILABILITY_LIST_TAG)
            .performScrollToNode(hasTestTag(INLINE_CENTER_ITEM_TAG))
        assertNodeCount(INLINE_CENTER_ITEM_TAG, expectedCount = 1)

        composeTestRule.onNodeWithTag(INLINE_AVAILABILITY_LIST_TAG)
            .performScrollToNode(hasTestTag(INLINE_BOTTOM_ITEM_TAG))
        assertNodeCount(INLINE_BOTTOM_ITEM_TAG, expectedCount = 1)
        composeTestRule.onNodeWithTag(INLINE_AVAILABILITY_LIST_TAG)
            .performScrollToNode(hasTestTag(INLINE_CENTER_ITEM_TAG))
        assertNodeCount(INLINE_CENTER_ITEM_TAG, expectedCount = 1)

        composeTestRule.runOnUiThread { isAvailable.value = false }
        assertNodeCount(INLINE_CENTER_ITEM_TAG, expectedCount = 0)

        composeTestRule.runOnUiThread { isAvailable.value = true }
        composeTestRule.onNodeWithTag(INLINE_AVAILABILITY_LIST_TAG)
            .performScrollToNode(hasTestTag(INLINE_CENTER_ITEM_TAG))
        assertNodeCount(INLINE_CENTER_ITEM_TAG, expectedCount = 1)
    }

    private fun assertNodeCount(tag: String, expectedCount: Int) {
        assertEquals(
            expectedCount,
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
        )
    }
}
