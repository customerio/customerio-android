package io.customer.commontest.tls

import android.annotation.SuppressLint
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS chains for exercising the SDK's certificate denylist.
 *
 * The emSign root and the "EM OV TLS CA - G3C" intermediate are the real, public certificates
 * from CVE-2026-111943, copied from OkHttp's CompromisedIntermediateTest. The intermediate's
 * leaked private key was the integer 4, which is how [forgedLeaf] was signed. That chain
 * validates against the emSign root, so it reproduces the attack a device trusting that root is
 * exposed to.
 *
 * The clean chain uses a throwaway root generated for these tests. Both leaves name only
 * `localhost`, so connecting by IP address makes the JDK consult the connection's hostname
 * verifier, as Android always does. All leaf keys are test-only and protect nothing.
 */
object CertificateFixtures {
    val emsignRoot: X509Certificate = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIICKTCCAa+gAwIBAgIQDnYGcvFDRZ/I/gqwvAXjlDAKBggqhkjOPQQDAzBWMQsw
        CQYDVQQGEwJJTjElMCMGA1UECgwcZU11ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRl
        ZDEgMB4GA1UEAwwXZW1TaWduIFJvb3QgVExTIENBIC0gRzMwHhcNMjQwMjA4MDgx
        OTMyWhcNNDkwMjA3MDgxOTMyWjBWMQswCQYDVQQGEwJJTjElMCMGA1UECgwcZU11
        ZGhyYSBUZWNobm9sb2dpZXMgTGltaXRlZDEgMB4GA1UEAwwXZW1TaWduIFJvb3Qg
        VExTIENBIC0gRzMwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAARfD9w/q1cWxL6D9bMG
        H6CQ4u+gAV6GN9s+PwQx9Bl4hC+0iU8QOk8mWO/RwXQy10DCSxT8vSIJClHKp+Ao
        4qJKdYeV0SwZ7LMQziyLfxIUOY6Grja/sQFwLKYf8EdDXAyjQjBAMB0GA1UdDgQW
        BBThOqshfPHjTVSu1HK3/YtZQiCQVDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/
        BAUwAwEB/zAKBggqhkjOPQQDAwNoADBlAjBhCFTkly7Yl7+s4hiw+k6ZJTXT2AiI
        rvmsmfqPr5E7ecwXb6kXA5LTFd6rjwTuV3sCMQC9w6ZSbVq1mveMO6dKFziOzClT
        KCT2RFT6yXtq6nzgRICgBmiXbJAXsmZ8yAwjyWc=
        -----END CERTIFICATE-----
        """
    )

    /** Its public key is on the SDK's certificate denylist. */
    val compromisedIntermediate: X509Certificate = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIDaDCCAu6gAwIBAgIPKWkmwiejoVJjkrGUgqUEMAoGCCqGSM49BAMDMFYxCzAJ
        BgNVBAYTAklOMSUwIwYDVQQKDBxlTXVkaHJhIFRlY2hub2xvZ2llcyBMaW1pdGVk
        MSAwHgYDVQQDDBdlbVNpZ24gUm9vdCBUTFMgQ0EgLSBHMzAeFw0yNTA0MzAxNTM3
        NDFaFw00MDA0MjgxODMwMDBaMEAxCzAJBgNVBAYTAlVTMRQwEgYDVQQKDAtlTXVk
        aHJhIEluYzEbMBkGA1UEAwwSRU0gT1YgVExTIENBIC0gRzNDMHYwEAYHKoZIzj0C
        AQYFK4EEACIDYgAEE4JRzVKskpjByKrZdzId65fnCb0LTKCspV3IrVHc/J0ViaFZ
        fjpRIOHv1jHGPhg1ysrimGmmLhYx6KKBgatWYW3EXZGKvAnzqw5jz3kqpNztc4e+
        N7ulaVSfHAKycO1no4IBljCCAZIwHwYDVR0jBBgwFoAU4TqrIXzx401UrtRyt/2L
        WUIgkFQwHQYDVR0OBBYEFDTq6gzryQra+dDF4u2beMmHnhtsMB0GA1UdJQQWMBQG
        CCsGAQUFBwMBBggrBgEFBQcDAjBdBgNVHSAEVjBUMDkGCysGAQQBg44hAQABMCow
        KAYIKwYBBQUHAgEWHGh0dHA6Ly9yZXBvc2l0b3J5LmVtc2lnbi5jb20wDQYLKwYB
        BAGDjiEBAm4wCAYGZ4EMAQICMHUGCCsGAQUFBwEBBGkwZzAkBggrBgEFBQcwAYYY
        aHR0cDovL29jc3AtYy5lbXNpZ24uY29tMD8GCCsGAQUFBzAChjNodHRwOi8vcmVw
        b3NpdG9yeS5lbXNpZ24uY29tL2NlcnRzL0VNT1ZUTFNDQUczQy5wN2MwNwYDVR0f
        BDAwLjAsoCqgKIYmaHR0cDovL2NybC5lbXNpZ24uY29tLz9yb290dGxzY2FnMy5j
        cmwwDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwCgYIKoZIzj0E
        AwMDaAAwZQIxAMSZt2xsPeumRdeSMSQ3CbzIZeQ/q7GDCWfeE1Pq+mhgA6gRM44c
        NVF3xJ+wsxJk+wIwFJbaDMokD8Ei6eNOiA8yUMYvfzQyngmrhZQMdPCK1SewrR79
        4rSb9vGqxZzelQth
        -----END CERTIFICATE-----
        """
    )

    /** CN=localhost, issued by [compromisedIntermediate]. */
    val forgedLeaf: X509Certificate = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIB8DCCAXegAwIBAgICEAIwCgYIKoZIzj0EAwMwQDELMAkGA1UEBhMCVVMxFDAS
        BgNVBAoMC2VNdWRocmEgSW5jMRswGQYDVQQDDBJFTSBPViBUTFMgQ0EgLSBHM0Mw
        HhcNMjYwMTAxMDAwMDAwWhcNNDYwMTAxMDAwMDAwWjAUMRIwEAYDVQQDDAlsb2Nh
        bGhvc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASMWzVIs50IUhf0syy5AHP0
        eT5P8j3G/cHDiVTPKR7QZ7XyKlHXbM+7zf9WIn3ljqF6kg2jI0PvtEQXexbacZeU
        o4GMMIGJMAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoG
        CCsGAQUFBwMBMBQGA1UdEQQNMAuCCWxvY2FsaG9zdDAdBgNVHQ4EFgQUy+N4ayyU
        ky9BX7yaD66BIKk5cBswHwYDVR0jBBgwFoAUNOrqDOvJCtr50MXi7Zt4yYeeG2ww
        CgYIKoZIzj0EAwMDZwAwZAIwECX18GrddBYXasto4C4kfoyFCfyWe7DozrUvFezd
        c2f0VQZU0p6Ibu3QgTjxx+OFAjAqLkletJbquNf1g6rNKVZT7jAtdQNHUs17Rcce
        zp7L6xrMBspFEqoduPzKtuE0Ggw=
        -----END CERTIFICATE-----
        """
    )

    val forgedLeafKey: PrivateKey = ecPrivateKey(
        """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg7Y+YBViBr9pIXeZc
        ctDrV6yHhZU11PQnl+bUZR5Oc7mhRANCAASMWzVIs50IUhf0syy5AHP0eT5P8j3G
        /cHDiVTPKR7QZ7XyKlHXbM+7zf9WIn3ljqF6kg2jI0PvtEQXexbacZeU
        -----END PRIVATE KEY-----
        """
    )

    val cleanRoot: X509Certificate = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBrDCCAVOgAwIBAgIUOpW2KcygW6pZxkWVSct0CB/FPIgwCgYIKoZIzj0EAwIw
        JDEiMCAGA1UEAwwZQ3VzdG9tZXIuaW8gU0RLIFRlc3QgUm9vdDAeFw0yNjAxMDEw
        MDAwMDBaFw00NjAxMDEwMDAwMDBaMCQxIjAgBgNVBAMMGUN1c3RvbWVyLmlvIFNE
        SyBUZXN0IFJvb3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT3CveOObHJ9zE9
        zhWVu8Fr1lroBobt27nA9lNWzTMSwSheHn1MGUaSCt5AtX8b2DNAjYbYeww3ENNd
        nrOtme8To2MwYTAdBgNVHQ4EFgQUfGLY8c7esBhghN0+UUsDDD8rOq8wHwYDVR0j
        BBgwFoAUfGLY8c7esBhghN0+UUsDDD8rOq8wDwYDVR0TAQH/BAUwAwEB/zAOBgNV
        HQ8BAf8EBAMCAQYwCgYIKoZIzj0EAwIDRwAwRAIgEX2qdxx4yKzUtJ9CnSUFEtH+
        gwwXWd+Z9SDjCIdjGT4CIA5tueFAAA9KDBdaW3VYWEVdvNDNypTwQC4CMTaDVa1W
        -----END CERTIFICATE-----
        """
    )

    /** CN=localhost, issued by [cleanRoot]. */
    val cleanLeaf: X509Certificate = certificate(
        """
        -----BEGIN CERTIFICATE-----
        MIIBtTCCAVugAwIBAgICIAIwCgYIKoZIzj0EAwIwJDEiMCAGA1UEAwwZQ3VzdG9t
        ZXIuaW8gU0RLIFRlc3QgUm9vdDAeFw0yNjAxMDEwMDAwMDBaFw00NjAxMDEwMDAw
        MDBaMBQxEjAQBgNVBAMMCWxvY2FsaG9zdDBZMBMGByqGSM49AgEGCCqGSM49AwEH
        A0IABLNuMe730kalxyGyqEshVdk1Yj0pus+ydrJ7/ymJI9I3OkdEees/5DxbNhJC
        ydxf6V3yIHk4NWAlcm4hyozSo6WjgYwwgYkwDAYDVR0TAQH/BAIwADAOBgNVHQ8B
        Af8EBAMCB4AwEwYDVR0lBAwwCgYIKwYBBQUHAwEwFAYDVR0RBA0wC4IJbG9jYWxo
        b3N0MB0GA1UdDgQWBBRrZzicB9+ApcZJNUL1DYlcjjIZyTAfBgNVHSMEGDAWgBR8
        Ytjxzt6wGGCE3T5RSwMMPys6rzAKBggqhkjOPQQDAgNIADBFAiEAyOWgCpJrcygI
        zLKF3xnsxY+krzdqRGhsuY1Sn2AHshECIHpZVZpLzUYmkPC30az8X4PdrHOPiNyS
        bFh2iCIuyXBY
        -----END CERTIFICATE-----
        """
    )

    val cleanLeafKey: PrivateKey = ecPrivateKey(
        """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgwF3HRJqKj0/yiWlm
        xD6oxNH6+P8+KgwTlEXKnP17HNGhRANCAASzbjHu99JGpcchsqhLIVXZNWI9KbrP
        snaye/8piSPSNzpHRHnrP+Q8WzYSQsncX+ld8iB5ODVgJXJuIcqM0qOl
        -----END PRIVATE KEY-----
        """
    )

    /** Server-side TLS context presenting [chain], leaf first. */
    fun serverSslContext(key: PrivateKey, vararg chain: X509Certificate): SSLContext {
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", key, password, chain)
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }.keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }

    /** Trust manager that trusts only [roots], standing in for a device trust store. */
    fun trustManagerFor(vararg roots: X509Certificate): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            roots.forEachIndexed { index, root -> setCertificateEntry("root-$index", root) }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    fun clientSslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    /**
     * Runs [block] with JDK `HttpsURLConnection` defaults that trust only [trustManager]'s roots
     * and accept every hostname, then restores the previous defaults.
     *
     * The JDK consults a connection's hostname verifier only when its own name check fails, while
     * Android always consults it. Pairing this with a request by IP address to a leaf that names
     * only `localhost` makes the JDK behave like Android: every handshake reaches the verifier,
     * and the accept-all default stands in for Android's passing name check.
     */
    fun <T> withHttpsUrlConnectionDefaults(trustManager: X509TrustManager, block: () -> T): T {
        val previousSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val previousHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientSslContext(trustManager).socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(acceptAllHostnames)
        try {
            return block()
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousSocketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(previousHostnameVerifier)
        }
    }

    private val acceptAllHostnames = HostnameVerifier { _, _ -> true }

    private fun certificate(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.trimIndent().toByteArray())) as X509Certificate

    // Test-only helper that runs on the JVM, where java.util.Base64 is always available.
    @SuppressLint("NewApi")
    private fun ecPrivateKey(pem: String): PrivateKey {
        val base64 = pem.trimIndent().lines().filterNot { it.startsWith("-----") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
    }
}
