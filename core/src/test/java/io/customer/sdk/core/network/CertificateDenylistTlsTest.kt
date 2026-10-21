package io.customer.sdk.core.network

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.tls.CertificateFixtures
import io.customer.commontest.tls.CertificateFixtures.cleanLeaf
import io.customer.commontest.tls.CertificateFixtures.cleanLeafKey
import io.customer.commontest.tls.CertificateFixtures.cleanRoot
import io.customer.commontest.tls.CertificateFixtures.compromisedIntermediate
import io.customer.commontest.tls.CertificateFixtures.emsignRoot
import io.customer.commontest.tls.CertificateFixtures.forgedLeaf
import io.customer.commontest.tls.CertificateFixtures.forgedLeafKey
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Runs real TLS handshakes against a local server. OkHttp invokes the hostname verifier the same
 * way on Android and on the JVM, so this covers the in-app, inbox, SSE, and Segment clients.
 */
class CertificateDenylistTlsTest : JUnit5Test() {

    private val server = MockWebServer()

    @AfterEach
    fun shutdownServer() {
        server.shutdown()
    }

    @Test
    fun request_whenDenylistIsNotApplied_thenCompromisedChainIsTrusted() {
        // Control case: proves the fixture is a working attack on a device that trusts emSign.
        startServer(forgedLeafKey, forgedLeaf, compromisedIntermediate)
        val client = clientTrusting(emsignRoot)

        val code = client.newCall(Request.Builder().url(server.url("/track")).build()).execute().use { it.code }

        code shouldBeEqualTo 200
        server.requestCount shouldBeEqualTo 1
    }

    @Test
    fun request_whenServerUsesCompromisedIntermediate_thenFailsBeforeRequestIsSent() {
        startServer(forgedLeafKey, forgedLeaf, compromisedIntermediate)
        val client = clientTrusting(emsignRoot).withDenylist()

        assertThrows<SSLPeerUnverifiedException> {
            client.newCall(Request.Builder().url(server.url("/track")).build()).execute()
        }

        server.requestCount shouldBeEqualTo 0
    }

    @Test
    fun request_whenServerChainIsClean_thenSucceeds() {
        startServer(cleanLeafKey, cleanLeaf)
        val client = clientTrusting(cleanRoot).withDenylist()

        val code = client.newCall(Request.Builder().url(server.url("/track")).build()).execute().use { it.code }

        code shouldBeEqualTo 200
        server.requestCount shouldBeEqualTo 1
    }

    private fun startServer(key: PrivateKey, vararg chain: X509Certificate) {
        server.useHttps(CertificateFixtures.serverSslContext(key, *chain).socketFactory, false)
        server.enqueue(MockResponse().setResponseCode(200))
        server.start(InetAddress.getByName("localhost"), 0)
    }

    private fun clientTrusting(root: X509Certificate): OkHttpClient {
        val trustManager = CertificateFixtures.trustManagerFor(root)
        return OkHttpClient.Builder()
            .sslSocketFactory(CertificateFixtures.clientSslContext(trustManager).socketFactory, trustManager)
            .build()
    }

    private fun OkHttpClient.withDenylist(): OkHttpClient =
        newBuilder().hostnameVerifier(DenylistHostnameVerifier(hostnameVerifier)).build()
}
