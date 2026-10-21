package io.customer.datapipelines.network

import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.RequestFactory
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.config.ScreenView
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.core.network.DenylistHostnameVerifier
import io.customer.sdk.data.model.Region
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class TrackingHttpClientTest : JUnitTest() {

    @Test
    fun createTrackingHttpClient_whenCreated_thenAppliesDenylistAndKeepsSegmentDefaults() {
        val client = createTrackingHttpClient()

        client.hostnameVerifier shouldBeEqualTo
            DenylistHostnameVerifier(OkHttpClient().hostnameVerifier, requireNotNull(client.x509TrustManager))
        client.protocols shouldBeEqualTo listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        client.connectTimeoutMillis shouldBeEqualTo 15_000
        client.readTimeoutMillis shouldBeEqualTo 20_000
    }

    @Test
    fun withCertificateDenylist_whenAlreadyApplied_thenReturnsSameClient() {
        val client = createTrackingHttpClient()

        client.withCertificateDenylist() shouldBe client
    }

    @Test
    fun updateAnalyticsConfig_whenApplied_thenSegmentTrafficUsesDenylistClient() {
        val configuration = Configuration(writeKey = "anyKey", application = null)

        updateAnalyticsConfig(moduleConfig = moduleConfig()).invoke(configuration)

        // Segment keeps the client in a private field. Reading it here also flags a future
        // analytics-kotlin upgrade that stops routing requests through this client.
        configuration.requestFactory.okHttpClient().hostnameVerifier
            .shouldBeInstanceOf<DenylistHostnameVerifier>()
    }

    private fun RequestFactory.okHttpClient(): OkHttpClient =
        RequestFactory::class.java.getDeclaredField("okHttpClient")
            .apply { isAccessible = true }
            .get(this) as OkHttpClient

    private fun moduleConfig() = DataPipelinesModuleConfig(
        cdpApiKey = "anyKey",
        region = Region.US,
        flushAt = 20,
        flushInterval = 30,
        flushPolicies = emptyList(),
        autoAddCustomerIODestination = true,
        trackApplicationLifecycleEvents = true,
        autoTrackDeviceAttributes = true,
        autoTrackActivityScreens = true,
        screenViewUse = ScreenView.All
    )
}
