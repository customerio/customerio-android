package io.customer.messaginginapp.gist.data.listeners

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.response.InboxMessageResponse
import io.customer.messaginginapp.gist.data.model.response.QueueMessagesResponse
import io.customer.messaginginapp.inbox.data.InboxFetchOutcome
import io.customer.messaginginapp.inbox.data.InboxRepository
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the Queue->InboxRepository wiring added to make the dormant visual-inbox
 * data layer LIVE:
 *  - the X-CIO-Inbox-Enabled header flipping true triggers the templates/branding fetch
 *    on the existing in-app lifecycle scope (the queue poll scope), and
 *  - an enabled poll with templates/branding missing triggers a fetch-if-missing, and
 *  - the live poll publishes inbox messages into the headless store (read on demand).
 *
 * The trigger path ([Queue.updateInboxFlag]) reads state, calls the repository, and
 * dispatches actions only -- it touches no network -- so it is invoked directly via
 * reflection here, avoiding the fixed-URL [GistEnvironment] HTTP path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueInboxTriggerTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private val mockRepository: InboxRepository = mockk(relaxed = true)

    private lateinit var manager: InAppMessagingManager
    private lateinit var queue: Queue

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency<InboxRepository>(mockRepository)
                    }
                }
            } + testConfig
        )

        coEvery { mockRepository.loadTemplatesAndBranding() } returns InboxFetchOutcome.Hidden("test")

        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = io.customer.sdk.data.model.Region.US
            ).build()
        ).attachToSDKComponent()

        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = "site",
                dataCenter = "us",
                environment = GistEnvironment.PROD
            )
        )
        manager.dispatch(InAppMessagingAction.SetUserIdentifier("user-1"))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        queue = Queue()
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    private fun invokeUpdateInboxFlag(headers: Headers) {
        val method = Queue::class.java.getDeclaredMethod("updateInboxFlag", Headers::class.java)
        method.isAccessible = true
        method.invoke(queue, headers)
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    // --- (a) enablement transition (false -> true) triggers the fetch ---

    @Test
    fun updateInboxFlag_givenEnabledHeaderTransition_expectFetchTriggered() {
        // State starts disabled; cache miss too, so transition is the trigger reason.
        every { mockRepository.isFetchInFlight } returns false
        every { mockRepository.needsTemplatesOrBrandingFetch() } returns true

        invokeUpdateInboxFlag(mapOf("X-CIO-Inbox-Enabled" to "true").toHeaders())

        // Enablement flipped on in state, and the fetch was launched on the queue scope.
        assert(manager.getCurrentState().isInboxEnabled) { "expected isInboxEnabled=true after transition" }
        coVerify(exactly = 1) { mockRepository.loadTemplatesAndBranding() }
    }

    // --- enabled + cache miss (no transition) still triggers fetch-if-missing ---

    @Test
    fun updateInboxFlag_givenAlreadyEnabledAndCacheMiss_expectFetchTriggered() {
        // Put state into enabled first (no transition on the call under test).
        manager.dispatch(InAppMessagingAction.SetInboxEnabled(true))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        every { mockRepository.isFetchInFlight } returns false
        every { mockRepository.needsTemplatesOrBrandingFetch() } returns true

        invokeUpdateInboxFlag(mapOf("X-CIO-Inbox-Enabled" to "true").toHeaders())

        coVerify(exactly = 1) { mockRepository.loadTemplatesAndBranding() }
    }

    // --- enabled + fresh cache (no transition) does NOT trigger a fetch ---

    @Test
    fun updateInboxFlag_givenAlreadyEnabledAndFreshCache_expectNoFetch() {
        manager.dispatch(InAppMessagingAction.SetInboxEnabled(true))
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
        every { mockRepository.isFetchInFlight } returns false
        every { mockRepository.needsTemplatesOrBrandingFetch() } returns false

        invokeUpdateInboxFlag(mapOf("X-CIO-Inbox-Enabled" to "true").toHeaders())

        coVerify(exactly = 0) { mockRepository.loadTemplatesAndBranding() }
    }

    // --- (b) the live poll publishes inbox messages into the headless store ---

    @Test
    fun handleSuccessfulFetch_givenInboxMessages_expectMessagesInState() {
        val response = QueueMessagesResponse(
            inAppMessages = emptyList(),
            inboxMessages = listOf(
                InboxMessageResponse(
                    queueId = "q1",
                    deliveryId = "d1",
                    sentAt = java.util.Date(),
                    topics = listOf("cio_inbox")
                )
            )
        )

        val method = Queue::class.java.getDeclaredMethod(
            "handleSuccessfulFetch",
            QueueMessagesResponse::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(queue, response, false)
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)

        // The poll published the mapped inbox messages into the headless store; the visual
        // inbox reads them on demand from state.inboxMessages (no separate message cache).
        val inboxMessages = manager.getCurrentState().inboxMessages
        assert(inboxMessages.size == 1 && inboxMessages.first().queueId == "q1") {
            "expected one inbox message (q1) in state, got $inboxMessages"
        }
    }

    // --- disabled header never triggers a fetch ---

    @Test
    fun updateInboxFlag_givenDisabledHeader_expectNoFetch() {
        every { mockRepository.isFetchInFlight } returns false
        every { mockRepository.needsTemplatesOrBrandingFetch() } returns true

        invokeUpdateInboxFlag(mapOf("X-CIO-Inbox-Enabled" to "false").toHeaders())

        assert(!manager.getCurrentState().isInboxEnabled) { "expected isInboxEnabled=false" }
        coVerify(exactly = 0) { mockRepository.loadTemplatesAndBranding() }
    }
}
