package io.customer.android.sample.kotlin_compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.data.sdk.InAppMessageEventListener
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
import io.customer.sdk.util.CioLogLevel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import leakcanary.AppWatcher
import leakcanary.DetectLeaksAfterTestSuccess
import leakcanary.LeakAssertions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MemoryLeakageInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val rule = DetectLeaksAfterTestSuccess()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var userPreferenceRepository: UserRepository

    @Inject
    lateinit var preferences: PreferenceRepository

    @Before
    fun setUp() {
        hiltRule.inject()

        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

        val configuration = runBlocking {
            preferences.getConfiguration().first()
        }

        CustomerIO.Builder(
            siteId = configuration.siteId,
            apiKey = configuration.apiKey,
            appContext = appContext as Application
        ).apply {
            setLogLevel(CioLogLevel.DEBUG)
            setBackgroundQueueMinNumberOfTasks(5)
            setBackgroundQueueSecondsDelay(5.0)
            addCustomerIOModule(
                ModuleMessagingInApp(
                    config = MessagingInAppModuleConfig.Builder()
                        .setEventListener(InAppMessageEventListener()).build()
                )
            )
            addCustomerIOModule(ModuleMessagingPushFCM())
            build()
        }

        // Delete all users. So we always land in `Login Screen`
        runBlocking {
            userPreferenceRepository.deleteAllUsers()
        }
    }

    @OptIn(InternalCustomerIOApi::class)
    @Test
    fun testMemoryLeakage() {
        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext

        // wait till compose ui is idle
        composeTestRule.runOnIdle { }

        // Launch the MainActivity.
        ActivityScenario.launch(MainActivity::class.java)

        // Enter the user's name and email address.
        val name =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_first_name_input))
        name.performTextInput("MemoryLeakageTest")

        val email = composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_email_input))
        email.performTextInput("memleak@leak.com")

        // Click the login button.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_login_button))
            .performClick()

        composeTestRule.runOnIdle { }

        // wait for 500ms
        Thread.sleep(500)

        // Click the random event button 50 times.
        val randomEvent =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_random_event_button))

        for (i in 0..50) {
            randomEvent.performClick()
        }

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the send custom event button.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_custom_event_button))
            .performClick()

        // Enter the event name, property name, and property value.
        val eventName =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_event_name_input))
        eventName.performTextInput("custom event test for memory leak")

        val propertyName =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_property_name_input))
        propertyName.performTextInput("test property")

        val propertyValue =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_property_value_input))
        propertyValue.performTextInput("test value")

        // Click the send button 10
        for (i in 0..10) {
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_send_event_button))
                .performClick()
        }

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag(
            appContext.getString(R.string.acd_back_button_icon),
            useUnmergedTree = true
        ).performClick()

        // Click the set device attribute button.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_device_attribute_button))
            .performClick()

        // Enter the attribute name and value.
        val attributeName =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_attribute_name_input))
        attributeName.performTextInput("memory test attribute")

        val attributeValue =
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_attribute_value_input))
        attributeValue.performTextInput("memory test attribute")

        // Click the send button 10
        for (i in 0..10) {
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_send_device_attribute_button))
                .performClick()
        }

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag(
            appContext.getString(R.string.acd_back_button_icon),
            useUnmergedTree = true
        ).performClick()

        // Click the set profile attribute button.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_profile_attribute_button))
            .performClick()

        // Enter the attribute name and value.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_attribute_name_input))
            .performTextInput("memory test attribute")

        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_attribute_value_input))
            .performTextInput("memory test attribute")

        // Click the send button 10
        for (i in 0..10) {
            composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_send_profile_attribute_button))
                .performClick()
        }

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag(
            appContext.getString(R.string.acd_back_button_icon),
            useUnmergedTree = true
        ).performClick()

        // wait till compose ui is idle
        composeTestRule.runOnIdle {}

        // Click the logout button.
        composeTestRule.onNodeWithTag(appContext.getString(R.string.acd_logout_button))
            .performClick()

        composeTestRule.runOnIdle { }

        // Tell the AppWatcher object that we expect the CustomerIO.instance() object to be weakly reachable.
        AppWatcher.objectWatcher.expectWeaklyReachable(
            CustomerIO.instance(),
            "CustomerIO should be garbage collected"
        )

        // Clear the CustomerIO instance.
        CustomerIO.clearInstance()

        // Call the System.gc() method to force the garbage collector to run.
        System.gc()

        // Assert that the AppWatcher object does not have any retained objects.
        assertEquals(AppWatcher.objectWatcher.hasRetainedObjects, false)
        assertEquals(AppWatcher.objectWatcher.retainedObjectCount, 0)
    }
}
