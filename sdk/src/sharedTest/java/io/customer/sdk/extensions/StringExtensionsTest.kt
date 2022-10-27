package io.customer.sdk.extensions

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

internal class StringExtensionsTest {

    @Test
    fun verify_activityScreenFormatting_expectFormattedScreenName() {
        "HomeActivity".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ActivityHome".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ActivityHomeActivity".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ItemsListActivity".getScreenNameFromActivity() shouldBeEqualTo "Items"
        "ItemsDialogActivity".getScreenNameFromActivity() shouldBeEqualTo "Items"
        "MapFragmentActivity".getScreenNameFromActivity() shouldBeEqualTo "Map"
    }
}
