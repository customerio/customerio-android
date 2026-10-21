package io.customer.messagingpush.util

import android.graphics.Bitmap
import io.customer.commontest.tls.CertificateFixtures
import io.customer.commontest.tls.CertificateFixtures.cleanLeaf
import io.customer.commontest.tls.CertificateFixtures.cleanLeafKey
import io.customer.commontest.tls.CertificateFixtures.cleanRoot
import io.customer.commontest.tls.CertificateFixtures.compromisedIntermediate
import io.customer.commontest.tls.CertificateFixtures.emsignRoot
import io.customer.commontest.tls.CertificateFixtures.forgedLeaf
import io.customer.commontest.tls.CertificateFixtures.forgedLeafKey
import io.customer.messagingpush.testutils.core.IntegrationTest
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.X509Certificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Downloads push notification images through real HTTPS handshakes. Removing the denylist from
 * [BitmapDownloader] makes the blocked-chain test fail.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BitmapDownloaderDenylistTest : IntegrationTest() {

    private val server = MockWebServer()
    private val deviceTrust = CertificateFixtures.trustManagerFor(emsignRoot, cleanRoot)

    @After
    fun shutdownServer() {
        server.shutdown()
    }

    @Test
    fun download_whenServerUsesCompromisedIntermediate_thenSkipsImageBeforeRequestIsSent() {
        startServer(forgedLeafKey, forgedLeaf, compromisedIntermediate)

        val result = CertificateFixtures.withHttpsUrlConnectionDefaults(deviceTrust) { download() }

        result.shouldBeNull()
        server.requestCount shouldBeEqualTo 0
    }

    @Test
    fun download_whenServerChainIsClean_thenDecodesImage() {
        startServer(cleanLeafKey, cleanLeaf)

        val result = CertificateFixtures.withHttpsUrlConnectionDefaults(deviceTrust) { download() }

        result.shouldNotBeNull()
        server.requestCount shouldBeEqualTo 1
    }

    private fun download(): Bitmap? =
        BitmapDownloader.download("https://127.0.0.1:${server.port}/image.png", 64, 64)

    private fun startServer(key: PrivateKey, vararg chain: X509Certificate) {
        server.useHttps(CertificateFixtures.serverSslContext(key, *chain).socketFactory, false)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pngImage())))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun pngImage(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }
}
