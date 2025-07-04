package io.customer.messaginginapp.gist.presentation

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.extensions.postOnUiThread
import io.customer.commontest.util.DispatchersProviderStub
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.utilities.ModalMessageExtras
import io.customer.messaginginapp.gist.utilities.ModalMessageParser
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GistModalActivityTest : IntegrationTest() {
    private val mockLogger = mockk<Logger>(relaxed = true)
    private val mockMessageParser = mockk<ModalMessageParser>(relaxed = true)
    private val dispatchersProviderStub = spyk(DispatchersProviderStub())
    private val scopeProviderStub = ScopeProviderStub.Standard()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                        overrideDependency<ModalMessageParser>(mockMessageParser)
                        overrideDependency<DispatchersProvider>(dispatchersProviderStub)
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                    }
                }
            }
        )
    }

    @Test
    fun onCreate_givenModuleMessagingInAppNotInitialized_expectActivityFinishesEarly() {
        // Create intent with valid message without initializing ModuleMessagingInApp
        val testMessage = createTestMessage()
        val intent = createActivityIntent(testMessage)

        // Launch activity - it should finish immediately due to module not being initialized
        val scenario = ActivityScenario.launch<GistModalActivity>(intent)

        // Now check that it is destroyed immediately
        scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
        // Verify correct error was logged
        assertCalledOnce {
            mockLogger.error(match { it.contains("ModuleMessagingInApp not initialized") })
        }
        scenario.close()
    }

    @Test
    fun onCreate_givenModuleMessagingInAppInitialized_expectActivityWorksNormally() {
        // Initialize ModuleMessagingInApp
        initializeModuleMessagingInApp()
        // Create intent with valid message with ModuleMessagingInApp initialized
        val testMessage = createTestMessage()
        val intent = createActivityIntent(testMessage)

        // Launch activity - it should work normally
        val scenario = ActivityScenario.launch<GistModalActivity>(intent)

        scenario.onActivity { activity ->
            // Activity should not be finishing immediately
            activity.isFinishing.shouldBeFalse()
            activity.isDestroyed.shouldBeFalse()
        }
        // Now check that it is resumed
        assert(scenario.state == Lifecycle.State.RESUMED)
        // Verify no error was logged
        verify(exactly = 0) { mockLogger.error(any()) }
        scenario.close()
    }

    @Test
    fun onCreate_givenNullMessageWithInitializedModule_expectActivityFinishes() {
        // Initialize ModuleMessagingInApp
        initializeModuleMessagingInApp()
        // Create intent without message (null message) with ModuleMessagingInApp initialized
        val intent = createActivityIntent(message = null)

        // Launch activity - it should finish normally due to null message
        val scenario = ActivityScenario.launch<GistModalActivity>(intent)

        // Wait for async parsing and activity lifecycle to complete before assertions
        postOnUiThread {
            // Now check that it is destroyed immediately
            scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
            // Verify correct error was logged
            assertCalledOnce {
                mockLogger.error(match { it.contains("Message is null") })
            }
            scenario.close()
        }
    }

    @Test
    fun onCreate_givenInvalidMessageWithInitializedModule_expectActivityFinishes() {
        // Initialize ModuleMessagingInApp
        initializeModuleMessagingInApp()
        // Create intent with invalid message with ModuleMessagingInApp initialized
        val intent = createActivityIntent(message = "test-message-id")

        // Launch activity - it should finish due to null message
        val scenario = ActivityScenario.launch<GistModalActivity>(intent)

        // Wait for async parsing and activity lifecycle to complete before assertions
        postOnUiThread {
            // Now check that it is destroyed immediately
            scenario.state shouldBeEqualTo Lifecycle.State.DESTROYED
            // Verify correct error was logged
            assertCalledOnce {
                mockLogger.error(match { it.contains("Message is null") })
            }
            scenario.close()
        }
    }

    @Test
    fun onCreate_givenSlowParsing_expectProperAsyncHandling() {
        // Initialize ModuleMessagingInApp
        initializeModuleMessagingInApp()

        val parseDelayMs = 500L
        val expectedResult = ModalMessageExtras(
            message = createTestMessage(),
            messagePosition = MessagePosition.CENTER
        )
        val intent = createActivityIntent(expectedResult.message)

        // Mock parser with intentional delay to test async behavior
        coEvery { mockMessageParser.parseExtras(any()) } coAnswers {
            delay(parseDelayMs) // Simulate realistic parsing time
            expectedResult
        }

        val scenario = ActivityScenario.launch<GistModalActivity>(intent)

        // Verify parsing was initiated
        coVerify(exactly = 1) { mockMessageParser.parseExtras(any()) }

        // Complete async operations
        scopeProviderStub.flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        scenario.onActivity { activity ->
            activity.isFinishing.shouldBeFalse()
        }

        assertCalledNever { mockLogger.error(any()) }

        // Allow time for any remaining async operations before cleanup
        postOnUiThread(delay = parseDelayMs) {
            scenario.close()
        }
    }

    private fun initializeModuleMessagingInApp() {
        val moduleConfig = MessagingInAppModuleConfig.Builder(
            siteId = "test-site-id",
            region = Region.US
        ).build()
        ModuleMessagingInApp(config = moduleConfig).attachToSDKComponent()

        val messagingManager = spyk(SDKComponent.inAppMessagingManager).also {
            SDKComponent.overrideDependency<InAppMessagingManager>(it)
        }
        messagingManager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = moduleConfig.siteId,
                dataCenter = moduleConfig.region.code,
                environment = GistEnvironment.LOCAL
            )
        ).flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    private fun createTestMessage(): Message = Message(
        messageId = "test-message-id",
        priority = 1
    )

    private fun createActivityIntent(message: Any? = null, position: MessagePosition? = null): Intent {
        return GistModalActivity.newIntent(ApplicationProvider.getApplicationContext()).apply {
            message?.let { putExtra(ModalMessageParser.EXTRA_IN_APP_MESSAGE, Gson().toJson(it)) }
            position?.let { putExtra(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION, it.name) }
        }
    }
}
