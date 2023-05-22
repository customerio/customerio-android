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
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
import io.customer.sdk.util.CioLogLevel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var userPreferenceRepository: UserRepository

    @Inject
    lateinit var preferences: PreferenceRepository

    @OptIn(ExperimentalCoroutinesApi::class)
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
        GlobalScope.launch {
            userPreferenceRepository.deleteAllUsers()
        }
    }

    @Test
    fun testMemoryLeakage() {
        // Launch the MainActivity.
        ActivityScenario.launch(MainActivity::class.java)

        // Enter the user's name and email address.
        val name = composeTestRule.onNodeWithTag("name")
        name.performTextInput("MemoryLeakageTest")

        val email = composeTestRule.onNodeWithTag("email")
        email.performTextInput("memleak@leak.com")

        // Click the login button.
        composeTestRule.onNodeWithTag("login").performClick()

        // Click the random event button 100 times.
        val randomEvent = composeTestRule.onNodeWithTag("random_event")
        for (i in 0..100) {
            randomEvent.performClick()
        }

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the send custom event button.
        composeTestRule.onNodeWithTag("send_custom_event").performClick()

        // Enter the event name, property name, and property value.
        val eventName = composeTestRule.onNodeWithTag("event_name")
        eventName.performTextInput("custom event test for memory leak")

        val propertyName = composeTestRule.onNodeWithTag("property_name")
        propertyName.performTextInput("test property")

        val propertyValue = composeTestRule.onNodeWithTag("property_value")
        propertyValue.performTextInput("test value")

        // Click the send button.
        composeTestRule.onNodeWithTag("send_button").performClick()

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag("back_button", useUnmergedTree = true).performClick()

        // Click the set device attribute button.
        composeTestRule.onNodeWithTag("set_device_attribute").performClick()

        // Enter the attribute name and value.
        val attributeName = composeTestRule.onNodeWithTag("attribute_name")
        attributeName.performTextInput("memory test attribute")

        val attributeValue = composeTestRule.onNodeWithTag("attribute_value")
        attributeValue.performTextInput("memory test attribute")

        // Click the send button.
        composeTestRule.onNodeWithTag("send_button").performClick()

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag("back_button", useUnmergedTree = true).performClick()

        // Click the set profile attribute button.
        composeTestRule.onNodeWithTag("set_profile_attribute").performClick()

        // Enter the attribute name and value.
        composeTestRule.onNodeWithTag("attribute_name").performTextInput("memory test attribute")

        composeTestRule.onNodeWithTag("attribute_value").performTextInput("memory test attribute")

        // Click the send button.
        composeTestRule.onNodeWithTag("send_button").performClick()

        // Assert that there are no leaks.
        LeakAssertions.assertNoLeaks()

        // Click the back button.
        composeTestRule.onNodeWithTag("back_button", useUnmergedTree = true).performClick()

        // wait for 5 seconds
        composeTestRule.runOnIdle {
            Thread.sleep(4000)
        }

        // Click the logout button.
        composeTestRule.onNodeWithTag("logout").performClick()
    }
}
