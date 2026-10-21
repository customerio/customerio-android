package io.customer.sdk.core.network

import android.net.http.X509TrustManagerExtensions
import io.customer.base.internal.InternalCustomerIOApi
import java.net.URLConnection
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Public keys of certificate authorities the SDK refuses to trust, even when the device's trust
 * store still does. Android only drops a CA through an OS or Google Play system update, which many
 * devices never receive, so the SDK carries its own list.
 *
 * Entries are SHA-256 hashes of each certificate's SubjectPublicKeyInfo, the same form OkHttp's
 * `CertificatePinner` uses. Matching on the key also blocks cross-signed copies of a certificate.
 */
@InternalCustomerIOApi
object CertificateDenylist {
    /**
     * CVE-2026-111943: eMudhra intermediates whose private keys leaked in October 2026. These are
     * the same hashes OkHttp blocks in its `CertificateBlocklist`.
     *
     * https://www.cve.org/CVERecord?id=CVE-2026-111943
     */
    private val blockedSpkiSha256: Set<String> = setOf(
        "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b",
        "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f",
        "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0",
        "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8",
        "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30",
        "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41",
        "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105",
        "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6"
    )

    /** Returns true when [certificate]'s public key is on the denylist. */
    fun isBlocked(certificate: Certificate): Boolean =
        spkiSha256Hex(certificate) in blockedSpkiSha256

    /** Returns true when any of [certificates] is on the denylist. */
    fun containsBlocked(certificates: Array<out Certificate>): Boolean =
        certificates.any(::isBlocked)

    private fun spkiSha256Hex(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * [HostnameVerifier] that runs [delegate] first, then rejects the session when the server's chain
 * contains a certificate on [CertificateDenylist].
 *
 * It checks two chains. The first is the one the server presented. The second is the chain the
 * platform actually trusted, which can include intermediates Android cached from earlier
 * handshakes. Without the second check, a server that once sent a blocked intermediate could later
 * send only its leaf and pass.
 *
 * OkHttp and Android's `HttpsURLConnection` call the hostname verifier right after the TLS
 * handshake and before writing the request. A blocked chain therefore fails the call before any
 * headers or body reach the server.
 */
@InternalCustomerIOApi
class DenylistHostnameVerifier internal constructor(
    private val delegate: HostnameVerifier,
    private val trustedChainBuilder: TrustedChainBuilder
) : HostnameVerifier {

    /**
     * Rebuilds chains with the device's default trust manager. Use it for `HttpsURLConnection`,
     * whose TLS trust manager is not reachable from app code.
     */
    constructor(delegate: HostnameVerifier) : this(delegate, PlatformTrustedChainBuilder)

    /**
     * Rebuilds chains with [trustManager]. Pass the trust manager the TLS handshake used, such as
     * OkHttp's `OkHttpClient.x509TrustManager`, so the rebuilt chain sees the same intermediate
     * cache as the handshake did.
     */
    constructor(delegate: HostnameVerifier, trustManager: X509TrustManager) :
        this(delegate, TrustManagerChainBuilder(trustManager))

    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        if (hostname == null || session == null || !delegate.verify(hostname, session)) return false
        val presentedChain = try {
            session.peerCertificates
        } catch (e: SSLPeerUnverifiedException) {
            return false
        }
        if (CertificateDenylist.containsBlocked(presentedChain)) return false

        // Null means the platform could not rebuild the chain. The presented chain was already
        // checked, so the connection proceeds as it would without this extra step.
        val trustedChain = trustedChainBuilder.build(
            presentedChain.filterIsInstance<X509Certificate>(),
            hostname
        ) ?: return true
        return trustedChain.none(CertificateDenylist::isBlocked)
    }

    // Android's HttpsURLConnection pools connections by address, and the address includes the
    // hostname verifier. Equal wrappers keep connections reusable across URLConnection instances.
    override fun equals(other: Any?): Boolean =
        other is DenylistHostnameVerifier &&
            other.delegate == delegate &&
            other.trustedChainBuilder == trustedChainBuilder

    override fun hashCode(): Int = 31 * delegate.hashCode() + trustedChainBuilder.hashCode()
}

/** Rebuilds the certificate chain the platform trusts for a server's [chain]. */
internal fun interface TrustedChainBuilder {
    /** Returns the trusted chain, leaf first, or null when it cannot be built. */
    fun build(chain: List<X509Certificate>, hostname: String): List<Certificate>?
}

/**
 * Builds the chain with [trustManager], the same way OkHttp's Android certificate chain cleaner
 * does. On Android the result includes intermediates the trust manager filled in from its cache.
 * Each trust manager keeps its own cache, so callers should pass the one their handshakes use.
 */
internal class TrustManagerChainBuilder(
    private val trustManager: X509TrustManager
) : TrustedChainBuilder {
    private val trustManagerExtensions: X509TrustManagerExtensions? by lazy {
        try {
            X509TrustManagerExtensions(trustManager)
        } catch (e: Exception) {
            // Missing outside Android, or this trust manager is not supported by the platform.
            null
        }
    }

    override fun build(chain: List<X509Certificate>, hostname: String): List<Certificate>? {
        val extensions = trustManagerExtensions ?: return null
        if (chain.isEmpty()) return null
        return try {
            // Android ignores the auth type for this check. OkHttp passes "RSA" as well.
            extensions.checkServerTrusted(chain.toTypedArray(), "RSA", hostname)
        } catch (e: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean =
        other is TrustManagerChainBuilder && other.trustManager === trustManager

    override fun hashCode(): Int = System.identityHashCode(trustManager)
}

/**
 * Builds the chain with the device's default trust manager.
 *
 * On Android 7 and later this is the app's shared Network Security Config trust manager, the same
 * one `HttpsURLConnection` handshakes use. On Android 5 and 6 every factory call creates a separate
 * trust manager with its own intermediate cache, so a chain the handshake completed from its
 * cache may not rebuild here. The verifier then falls back to the chain the server presented.
 */
internal object PlatformTrustedChainBuilder : TrustedChainBuilder {
    private val delegate: TrustedChainBuilder? by lazy {
        try {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
                ?.let(::TrustManagerChainBuilder)
        } catch (e: Exception) {
            null
        }
    }

    override fun build(chain: List<X509Certificate>, hostname: String): List<Certificate>? =
        delegate?.build(chain, hostname)
}

/**
 * Wraps this connection's current hostname verifier in a [DenylistHostnameVerifier]. Call it
 * before connecting. Plain HTTP connections are left unchanged.
 */
@InternalCustomerIOApi
fun URLConnection.applyCertificateDenylist() {
    if (this is HttpsURLConnection && hostnameVerifier !is DenylistHostnameVerifier) {
        hostnameVerifier = DenylistHostnameVerifier(hostnameVerifier)
    }
}
