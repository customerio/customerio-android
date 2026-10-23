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
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.mockk.mockk
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A 304 replays the last cached 200 body. The server answers 304 per user, so the queue body belongs
 * to the identity it was fetched for: after a user switch a 304 must not serve the previous user's
 * messages, and a response that arrives after the switch must not become the cached body. Other
 * responses on the shared client are workspace scoped and keep plain URL caching.
 */
@RunWith(RobolectricTestRunner::class)
class QueueResponseCacheTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private lateinit var manager: InAppMessagingManager
    private lateinit var queue: Queue

    private val queueUrl = "https://gist.example.com/api/v4/users?sessionId=session-1"
    private val templatesUrl = "https://gist.example.com/api/v1/inbox/templates"
    private val body = """{"inAppMessages":[],"inboxMessages":[]}"""

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                    }
                }
            } + testConfig
        )

        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(
                siteId = TestConstants.Keys.SITE_ID,
                region = io.customer.sdk.data.model.Region.US
            ).build()
        ).attachToSDKComponent()

        manager = SDKComponent.inAppMessagingManager
        manager.dispatch(InAppMessagingAction.Initialize(siteId = "site", dataCenter = "us", environment = GistEnvironment.PROD))
        queue = Queue(queueServiceOverride = mockk(relaxed = true))
    }

    override fun teardown() {
        manager.dispatch(InAppMessagingAction.Reset)
        super.teardown()
    }

    private fun identify(userId: String) = dispatch(InAppMessagingAction.SetUserIdentifier(userId))

    private fun dispatch(action: InAppMessagingAction) {
        manager.dispatch(action)
        flushCoroutines(scopeProviderStub.inAppLifecycleScope)
    }

    /** Intercepts a response to a request sent with [requestState], the state the interceptor snapshots. */
    private fun intercept(url: String, code: Int, requestState: InAppMessagingState = manager.getCurrentState()): Response {
        val request = Request.Builder().url(url).post("{}".toRequestBody()).build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body((if (code == 200) body else "").toResponseBody())
            .build()
        val method = Queue::class.java.getDeclaredMethod(
            "interceptResponse",
            Response::class.java,
            Request::class.java,
            InAppMessagingState::class.java
        )
        method.isAccessible = true
        return method.invoke(queue, response, request, requestState) as Response
    }

    @Test
    fun notModified_givenSameUser_expectCachedBodyServed() {
        identify("user-a")
        intercept(queueUrl, 200)

        val response = intercept(queueUrl, 304)

        assert(response.code == 200) { "expected the cached body to be served, got ${response.code}" }
        assert(response.body?.string() == body)
    }

    @Test
    fun notModified_givenAnotherUser_expectPassedThrough() {
        identify("user-a")
        intercept(queueUrl, 200)

        identify("user-b")
        val response = intercept(queueUrl, 304)

        assert(response.code == 304) { "expected the previous user's body not to be served, got ${response.code}" }
    }

    @Test
    fun notModified_givenAnonymousUserIdentified_expectPassedThrough() {
        dispatch(InAppMessagingAction.SetAnonymousIdentifier("anon-1"))
        intercept(queueUrl, 200)

        identify("user-a")
        val response = intercept(queueUrl, 304)

        assert(response.code == 304) { "expected the anonymous body not to be served, got ${response.code}" }
    }

    @Test
    fun notModified_givenBodyCachedWithoutOwner_expectPassedThrough() {
        // A body cached before its owner was recorded; whose it is can't be told.
        SDKComponent.inAppPreferenceStore.saveNetworkResponse(queueUrl, body)
        identify("user-a")

        val response = intercept(queueUrl, 304)

        assert(response.code == 304) { "expected an ownerless body not to be served, got ${response.code}" }
    }

    @Test
    fun ok_givenUserChangedWhileInFlight_expectNotCached() {
        identify("user-a")
        val requestState = manager.getCurrentState()

        identify("user-b")
        intercept(queueUrl, 200, requestState)

        assert(SDKComponent.inAppPreferenceStore.getNetworkResponse(queueUrl) == null) {
            "expected the previous user's response not to be cached"
        }
    }

    @Test
    fun notModified_givenNonQueueRequest_expectUrlCachingUnchanged() {
        identify("user-a")
        intercept(templatesUrl, 200)

        identify("user-b")
        val response = intercept(templatesUrl, 304)

        assert(response.code == 200) { "expected workspace-scoped responses to stay URL cached, got ${response.code}" }
    }
}
