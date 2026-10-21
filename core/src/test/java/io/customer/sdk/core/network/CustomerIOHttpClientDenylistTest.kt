package io.customer.sdk.core.network

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.tls.CertificateFixtures
import io.customer.commontest.tls.CertificateFixtures.cleanLeaf
import io.customer.commontest.tls.CertificateFixtures.cleanLeafKey
import io.customer.commontest.tls.CertificateFixtures.cleanRoot
import io.customer.commontest.tls.CertificateFixtures.compromisedIntermediate
import io.customer.commontest.tls.CertificateFixtures.emsignRoot
import io.customer.commontest.tls.CertificateFixtures.forgedLeaf
import io.customer.commontest.tls.CertificateFixtures.forgedLeafKey
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.model.Settings
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives [CustomerIOHttpClientImpl], which geofence and push delivery tracking use, through real
 * HTTPS handshakes. Removing the denylist from the client makes the blocked-chain test fail.
 */
@RunWith(RobolectricTestRunner::class)
class CustomerIOHttpClientDenylistTest : RobolectricTest() {

    private val server = MockWebServer()
    private val deviceTrust = CertificateFixtures.trustManagerFor(emsignRoot, cleanRoot)

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { argument(ApplicationArgument(applicationMock)) })
    }

    @After
    fun shutdownServer() {
        server.shutdown()
    }

    @Test
    fun request_whenConnectionHasNoDenylist_thenForgedChainIsAccepted() {
        // Control case: shows the forged chain passes the test trust store on its own.
        startServer(forgedLeafKey, forgedLeaf, compromisedIntermediate)

        val code = CertificateFixtures.withHttpsUrlConnectionDefaults(deviceTrust) {
            val connection = URL("https://127.0.0.1:${server.port}/").openConnection() as HttpsURLConnection
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }

        code shouldBeEqualTo 200
    }

    @Test
    fun request_whenServerUsesCompromisedIntermediate_thenFailsBeforeRequestIsSent() {
        startServer(forgedLeafKey, forgedLeaf, compromisedIntermediate)

        val result = CertificateFixtures.withHttpsUrlConnectionDefaults(deviceTrust) { request() }

        result.isFailure.shouldBeTrue()
        result.exceptionOrNull().shouldBeInstanceOf<IOException>()
        server.requestCount shouldBeEqualTo 0
    }

    @Test
    fun request_whenServerChainIsClean_thenSucceeds() {
        startServer(cleanLeafKey, cleanLeaf)

        val result = CertificateFixtures.withHttpsUrlConnectionDefaults(deviceTrust) { request() }

        result.getOrNull() shouldBeEqualTo "ok"
        server.requestCount shouldBeEqualTo 1
    }

    private fun request(): Result<String> {
        SDKComponent.android().globalPreferenceStore.saveSettings(
            Settings(writeKey = "test-key", apiHost = "127.0.0.1:${server.port}")
        )
        return runBlocking {
            CustomerIOHttpClientImpl().request(HttpRequestParams(path = "/test", method = HttpMethod.GET))
        }
    }

    private fun startServer(key: PrivateKey, vararg chain: X509Certificate) {
        server.useHttps(CertificateFixtures.serverSslContext(key, *chain).socketFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }
}
