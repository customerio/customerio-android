package io.customer.sdk.core.network

import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.tls.CertificateFixtures
import io.customer.commontest.tls.CertificateFixtures.cleanLeaf
import io.customer.commontest.tls.CertificateFixtures.cleanRoot
import io.customer.commontest.tls.CertificateFixtures.compromisedIntermediate
import io.customer.commontest.tls.CertificateFixtures.emsignRoot
import io.customer.commontest.tls.CertificateFixtures.forgedLeaf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeInstanceOf
import org.junit.jupiter.api.Test

class DenylistHostnameVerifierTest : JUnit5Test() {

    private val acceptAll = HostnameVerifier { _, _ -> true }

    // Stands in for a platform that cannot rebuild the chain, so only the presented chain counts.
    private val noPlatformChain = TrustedChainBuilder { _, _ -> null }

    @Test
    fun verify_whenDelegateRejects_thenReturnsFalseWithoutReadingCertificates() {
        val session = mockk<SSLSession>()
        val verifier = DenylistHostnameVerifier({ _, _ -> false }, noPlatformChain)

        verifier.verify("cdp.customer.io", session).shouldBeFalse()

        verify(exactly = 0) { session.peerCertificates }
    }

    @Test
    fun verify_whenPeerChainContainsBlockedCertificate_thenReturnsFalse() {
        val session = sessionPresenting(forgedLeaf, compromisedIntermediate)

        DenylistHostnameVerifier(acceptAll, noPlatformChain).verify("cdp.customer.io", session).shouldBeFalse()
    }

    @Test
    fun verify_whenPeerChainIsClean_thenReturnsTrue() {
        val session = sessionPresenting(cleanLeaf, cleanRoot)

        DenylistHostnameVerifier(acceptAll, noPlatformChain).verify("cdp.customer.io", session).shouldBeTrue()
    }

    @Test
    fun verify_whenPeerIsUnverified_thenReturnsFalse() {
        val session = mockk<SSLSession> {
            every { peerCertificates } throws SSLPeerUnverifiedException("no certificates")
        }

        DenylistHostnameVerifier(acceptAll, noPlatformChain).verify("cdp.customer.io", session).shouldBeFalse()
    }

    @Test
    fun verify_whenSessionIsNull_thenReturnsFalse() {
        DenylistHostnameVerifier(acceptAll, noPlatformChain).verify("cdp.customer.io", null).shouldBeFalse()
    }

    @Test
    fun verify_whenPlatformChainAddsCachedBlockedIntermediate_thenReturnsFalse() {
        // The server sends only its leaf. The platform completes the chain from its cache of
        // intermediates seen in earlier handshakes, including the compromised one.
        val session = sessionPresenting(forgedLeaf)
        val cachedChain = TrustedChainBuilder { _, _ -> listOf(forgedLeaf, compromisedIntermediate, emsignRoot) }

        DenylistHostnameVerifier(acceptAll, cachedChain).verify("cdp.customer.io", session).shouldBeFalse()
    }

    @Test
    fun verify_whenPlatformChainIsClean_thenReturnsTrue() {
        val session = sessionPresenting(cleanLeaf)
        val platformChain = TrustedChainBuilder { _, _ -> listOf(cleanLeaf, cleanRoot) }

        DenylistHostnameVerifier(acceptAll, platformChain).verify("cdp.customer.io", session).shouldBeTrue()
    }

    @Test
    fun verify_whenPresentedChainIsBlocked_thenReturnsFalseWithoutBuildingPlatformChain() {
        var buildCalls = 0
        val platformChain = TrustedChainBuilder { _, _ -> buildCalls++; null }

        DenylistHostnameVerifier(acceptAll, platformChain)
            .verify("cdp.customer.io", sessionPresenting(forgedLeaf, compromisedIntermediate))
            .shouldBeFalse()

        buildCalls shouldBeEqualTo 0
    }

    @Test
    fun verify_whenPlatformBuildsChain_thenPassesPresentedCertificatesAndHostname() {
        var received: Pair<List<X509Certificate>, String>? = null
        val platformChain = TrustedChainBuilder { chain, hostname -> received = chain to hostname; chain }

        DenylistHostnameVerifier(acceptAll, platformChain).verify("cdp.customer.io", sessionPresenting(cleanLeaf, cleanRoot))

        received shouldBeEqualTo (listOf(cleanLeaf, cleanRoot) to "cdp.customer.io")
    }

    @Test
    fun equals_whenDelegatesMatch_thenWrappersAreEqual() {
        val first = DenylistHostnameVerifier(acceptAll)
        val second = DenylistHostnameVerifier(acceptAll)

        first shouldBeEqualTo second
        first.hashCode() shouldBeEqualTo second.hashCode()
    }

    @Test
    fun equals_whenDelegatesDiffer_thenWrappersAreNotEqual() {
        DenylistHostnameVerifier(acceptAll) shouldNotBeEqualTo DenylistHostnameVerifier { _, _ -> true }
    }

    @Test
    fun equals_whenTrustManagersMatch_thenWrappersAreEqual() {
        val trustManager = CertificateFixtures.trustManagerFor(cleanRoot)

        DenylistHostnameVerifier(acceptAll, trustManager) shouldBeEqualTo DenylistHostnameVerifier(acceptAll, trustManager)
    }

    @Test
    fun equals_whenTrustManagersDiffer_thenWrappersAreNotEqual() {
        // Each trust manager has its own intermediate cache, so wrappers over different ones differ.
        DenylistHostnameVerifier(acceptAll, CertificateFixtures.trustManagerFor(cleanRoot)) shouldNotBeEqualTo
            DenylistHostnameVerifier(acceptAll, CertificateFixtures.trustManagerFor(cleanRoot))
    }

    @Test
    fun build_whenTrustManagerIsNotSupportedByPlatform_thenReturnsNull() {
        // A JVM trust manager stands in for one Android cannot rebuild chains with.
        TrustManagerChainBuilder(CertificateFixtures.trustManagerFor(cleanRoot))
            .build(listOf(cleanLeaf), "localhost")
            .shouldBeNull()
    }

    @Test
    fun applyCertificateDenylist_whenConnectionsShareDefaultVerifier_thenWrappersAreEqual() {
        // Keeps Android's HttpsURLConnection pool able to reuse connections between downloads.
        val first = httpsConnection().apply { applyCertificateDenylist() }
        val second = httpsConnection().apply { applyCertificateDenylist() }

        first.hostnameVerifier shouldBeEqualTo second.hostnameVerifier
    }

    @Test
    fun applyCertificateDenylist_whenHttpsConnection_thenWrapsExistingVerifier() {
        var delegateCalls = 0
        val connection = httpsConnection().apply {
            hostnameVerifier = HostnameVerifier { _, _ -> delegateCalls++; true }
        }

        connection.applyCertificateDenylist()

        connection.hostnameVerifier.shouldBeInstanceOf<DenylistHostnameVerifier>()
        connection.hostnameVerifier.verify("example.com", sessionPresenting(cleanLeaf)).shouldBeTrue()
        connection.hostnameVerifier.verify("example.com", sessionPresenting(forgedLeaf, compromisedIntermediate)).shouldBeFalse()
        delegateCalls shouldBeEqualTo 2
    }

    @Test
    fun applyCertificateDenylist_whenCalledTwice_thenWrapsOnce() {
        var delegateCalls = 0
        val connection = httpsConnection().apply {
            hostnameVerifier = HostnameVerifier { _, _ -> delegateCalls++; true }
        }

        connection.applyCertificateDenylist()
        connection.applyCertificateDenylist()
        connection.hostnameVerifier.verify("example.com", sessionPresenting(cleanLeaf))

        delegateCalls shouldBeEqualTo 1
    }

    @Test
    fun applyCertificateDenylist_whenPlainHttpConnection_thenLeavesConnectionUnchanged() {
        val connection = URL("http://example.com/").openConnection() as HttpURLConnection

        connection.applyCertificateDenylist()

        connection.shouldNotBeInstanceOf<HttpsURLConnection>()
    }

    // Opening a URLConnection does not touch the network until connect() is called.
    private fun httpsConnection(): HttpsURLConnection =
        URL("https://example.com/").openConnection() as HttpsURLConnection

    private fun sessionPresenting(vararg certificates: Certificate): SSLSession =
        mockk { every { peerCertificates } returns arrayOf(*certificates) }
}
