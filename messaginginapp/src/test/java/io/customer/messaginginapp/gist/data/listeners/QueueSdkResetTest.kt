package io.customer.messaginginapp.gist.data.listeners

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.extensions.flushCoroutines
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueSdkResetTest : IntegrationTest() {
    private val scopes = ScopeProviderStub.Unconfined()

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk { overrideDependency<ScopeProvider>(scopes) }
                }
            } + testConfig
        )
        ModuleMessagingInApp(MessagingInAppModuleConfig.Builder("site", Region.US).build())
            .attachToSDKComponent()
        SDKComponent.inAppMessagingManager.dispatch(
            InAppMessagingAction.Initialize(siteId = "site", dataCenter = "us", environment = GistEnvironment.PROD)
        )
        SDKComponent.inAppMessagingManager.dispatch(InAppMessagingAction.SetUserIdentifier("user-1"))
        flushCoroutines(scopes.inAppLifecycleScope)
    }

    @Test
    fun testHttpClient_whenSdkResetBeforeRequest_thenFailsWithoutUncaughtWorkerException() {
        val result = executeAcrossReset()

        assertNoWorkerException(result.uncaught)
        assertNotNull("The request must report an IOException", result.failure)
        assertNull("A request without SDK headers must not be sent", result.request)
    }

    @Test
    fun testHttpClient_whenSdkResetBefore200Response_thenFailsAndClosesBody() {
        assertLateResponseFailsSafely(200)
    }

    @Test
    fun testHttpClient_whenSdkResetBefore304Response_thenFailsAndClosesBody() {
        assertLateResponseFailsSafely(304)
    }

    @Test
    fun testHttpClient_whenSdkInitialized_thenReuses200BodyFor304Response() {
        var responseCode = 200
        val fetchedBody = """{"messages":[]}"""
        val client = Queue().httpClient.newBuilder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("synthetic")
                    .body((if (responseCode == 200) fetchedBody else "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val request = Request.Builder().url("https://example.invalid/api/v4/users").build()

        try {
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(fetchedBody, response.body?.string())
                assertNull(response.header("X-CIO-MOBILE-SDK-Cache"))
            }
            responseCode = 304
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(fetchedBody, response.body?.string())
                assertEquals("true", response.header("X-CIO-MOBILE-SDK-Cache"))
            }
        } finally {
            client.cache?.close()
        }
    }

    private fun assertLateResponseFailsSafely(code: Int) {
        val result = executeAcrossReset(responseCode = code)

        assertNoWorkerException(result.uncaught)
        assertNotNull("The late response must report an IOException", result.failure)
        assertTrue("A dropped response must close its body", result.bodyClosed)
        assertEquals("com.example.test_app", result.request?.header("X-CIO-Client-App-Identifier"))
        assertEquals("site", result.request?.header("X-CIO-Site-Id"))
        assertEquals("us", result.request?.header("X-CIO-Datacenter"))
        assertEquals("android-android", result.request?.header("X-CIO-Client-Platform"))
        assertEquals("false", result.request?.header("X-Gist-User-Anonymous"))
        assertEquals("dXNlci0x", result.request?.header("X-Gist-Encoded-User-Token"))
    }

    private fun assertNoWorkerException(error: Throwable?) {
        if (error != null) {
            throw AssertionError("An SDK teardown must not crash the HTTP worker", error)
        }
    }

    private data class CallResult(
        val failure: IOException?,
        val uncaught: Throwable?,
        val request: Request?,
        val bodyClosed: Boolean
    )

    private fun executeAcrossReset(responseCode: Int? = null): CallResult {
        val failure = AtomicReference<IOException?>()
        val uncaught = AtomicReference<Throwable?>()
        val request = AtomicReference<Request?>()
        val callbackFinished = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val workerMayStart = CountDownLatch(if (responseCode == null) 1 else 0)
        val requestReached = CountDownLatch(1)
        val resetFinished = CountDownLatch(if (responseCode == null) 0 else 1)
        val bodyClosed = AtomicBoolean(false)
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() {
                bodyClosed.set(true)
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 2L
            override fun source() = source
        }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread({
                try {
                    check(workerMayStart.await(5, TimeUnit.SECONDS))
                    task.run()
                } catch (error: Throwable) {
                    // Observe what would escape OkHttp's worker without changing the global handler.
                    uncaught.set(error)
                } finally {
                    workerFinished.countDown()
                }
            }, "queue-sdk-reset-test")
        }
        val client = Queue().httpClient.newBuilder()
            .dispatcher(Dispatcher(executor))
            .addInterceptor { chain ->
                // Terminate at a controlled response, after the real Queue request interceptor.
                // No external network is involved, and Queue still processes the response itself.
                request.set(chain.request())
                requestReached.countDown()
                check(resetFinished.await(5, TimeUnit.SECONDS))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode ?: 200)
                    .message("synthetic")
                    .body(body)
                    .build()
            }
            .build()

        try {
            client.newCall(Request.Builder().url("https://example.invalid/api/v4/users").build())
                .enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        failure.set(e)
                        callbackFinished.countDown()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        callbackFinished.countDown()
                    }
                })

            if (responseCode == null) {
                // Accept the call while initialized, then reset before its worker starts.
                SDKComponent.reset()
                workerMayStart.countDown()
            } else {
                assertTrue("Request did not reach the response fixture", requestReached.await(5, TimeUnit.SECONDS))
                SDKComponent.reset()
                resetFinished.countDown()
            }
            assertTrue("HTTP callback did not complete", callbackFinished.await(5, TimeUnit.SECONDS))
            executor.shutdown()
            // OkHttp may invoke onFailure and then rethrow. Wait for the worker, not just the callback.
            assertTrue("HTTP worker did not finish", workerFinished.await(5, TimeUnit.SECONDS))
            return CallResult(failure.get(), uncaught.get(), request.get(), bodyClosed.get())
        } finally {
            workerMayStart.countDown()
            resetFinished.countDown()
            client.dispatcher.cancelAll()
            executor.shutdownNow()
            assertTrue("HTTP executor did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
            body.close()
            client.cache?.close()
        }
    }
}
