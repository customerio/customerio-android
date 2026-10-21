package io.customer.messaginginapp.gist.data

import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.TestConstants
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.listeners.Queue
import io.customer.messaginginapp.gist.data.sse.SseService
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.network.DenylistHostnameVerifier
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppHttpClientDenylistTest : IntegrationTest() {

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        ModuleMessagingInApp(
            config = MessagingInAppModuleConfig.Builder(siteId = TestConstants.Keys.SITE_ID).build()
        ).attachToSDKComponent()
    }

    @Test
    fun withCertificateDenylist_whenClientHasNoDenylist_thenWrapsVerifierAndKeepsInterceptors() {
        val interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val wrapped = client.withCertificateDenylist()

        wrapped.hostnameVerifier.shouldBeInstanceOf<DenylistHostnameVerifier>()
        wrapped.interceptors shouldBeEqualTo listOf(interceptor)
        wrapped.x509TrustManager shouldBe client.x509TrustManager
        wrapped.connectionPool shouldBe client.connectionPool
    }

    @Test
    fun withCertificateDenylist_whenClientHasTrustManager_thenRebuildsChainsWithIt() {
        val client = OkHttpClient()
        val trustManager = requireNotNull(client.x509TrustManager)

        client.withCertificateDenylist().hostnameVerifier shouldBeEqualTo
            DenylistHostnameVerifier(client.hostnameVerifier, trustManager)
    }

    @Test
    fun withCertificateDenylist_whenAlreadyApplied_thenReturnsSameClient() {
        val wrapped = OkHttpClient().withCertificateDenylist()

        wrapped.withCertificateDenylist() shouldBe wrapped
    }

    @Test
    fun httpClient_whenQueueCreated_thenAppliesDenylist() {
        // The inbox API derives its client from this one, so it inherits the verifier.
        Queue(queueServiceOverride = mockk(relaxed = true))
            .httpClient.hostnameVerifier.shouldBeInstanceOf<DenylistHostnameVerifier>()
    }

    @Test
    fun httpClient_whenSseServiceCreated_thenAppliesDenylist() {
        val service = SseService(sseLogger = mockk(relaxed = true), inAppMessagingManager = mockk(relaxed = true))

        val client = SseService::class.java.getDeclaredField("httpClient")
            .apply { isAccessible = true }
            .get(service) as OkHttpClient

        client.hostnameVerifier.shouldBeInstanceOf<DenylistHostnameVerifier>()
    }
}
