package io.customer.datapipelines.extensions
import io.customer.datapipelines.plugins.getScreenNameFromActivity
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

internal class StringExt {

    @Test
    fun verify_activityScreenFormatting_expectFormattedScreenName() {
        "HomeActivity".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ActivityHome".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ActivityHomeActivity".getScreenNameFromActivity() shouldBeEqualTo "Home"
        "ItemsListActivity".getScreenNameFromActivity() shouldBeEqualTo "Items"
        "ItemsDialogActivity".getScreenNameFromActivity() shouldBeEqualTo "Items"
        "MapFragmentActivity".getScreenNameFromActivity() shouldBeEqualTo "Map"
        "SplashScreen".getScreenNameFromActivity() shouldBeEqualTo "SplashScreen"
    }
}
