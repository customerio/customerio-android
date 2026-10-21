package io.customer.messaginginapp.gist.data.listeners

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppPreferenceStore
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
 * A 304 replays the last cached 200 body. The queue body belongs to the identity it was fetched for,
 * so after a user switch a 304 must not serve the previous user's messages. Other responses on the
 * shared client are workspace scoped and keep plain URL caching.
 */
@RunWith(RobolectricTestRunner::class)
class QueueResponseCacheTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private lateinit var queue: Queue

    private val queueUrl = "https://gist.example.com/api/v4/users?sessionId=session-1"
    private val templatesUrl = "https://gist.example.com/api/v1/inbox/templates"
    private val body = """{"inAppMessages":[],"inboxMessages":[]}"""

    private val userA = InAppMessagingState(userId = "user-a")
    private val userB = InAppMessagingState(userId = "user-b")

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

        queue = Queue(queueServiceOverride = mockk(relaxed = true))
    }

    private fun intercept(url: String, code: Int, requestState: InAppMessagingState): Response {
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
        intercept(queueUrl, 200, userA)

        val response = intercept(queueUrl, 304, userA)

        assert(response.code == 200) { "expected the cached body to be served, got ${response.code}" }
        assert(response.body?.string() == body)
    }

    @Test
    fun notModified_givenAnotherUser_expectPassedThrough() {
        intercept(queueUrl, 200, userA)

        val response = intercept(queueUrl, 304, userB)

        assert(response.code == 304) { "expected the previous user's body not to be served, got ${response.code}" }
    }

    @Test
    fun notModified_givenAnonymousUserIdentified_expectPassedThrough() {
        intercept(queueUrl, 200, InAppMessagingState(anonymousId = "anon-1"))

        val response = intercept(queueUrl, 304, InAppMessagingState(userId = "user-a", anonymousId = "anon-1"))

        assert(response.code == 304) { "expected the anonymous body not to be served, got ${response.code}" }
    }

    @Test
    fun notModified_givenBodyCachedWithoutOwner_expectPassedThrough() {
        // A body cached before its owner was recorded; whose it is can't be told.
        SDKComponent.inAppPreferenceStore.saveNetworkResponse(queueUrl, body)

        val response = intercept(queueUrl, 304, userA)

        assert(response.code == 304) { "expected an ownerless body not to be served, got ${response.code}" }
    }

    @Test
    fun notModified_givenNonQueueRequest_expectUrlCachingUnchanged() {
        intercept(templatesUrl, 200, userA)

        val response = intercept(templatesUrl, 304, userB)

        assert(response.code == 200) { "expected workspace-scoped responses to stay URL cached, got ${response.code}" }
    }
}
