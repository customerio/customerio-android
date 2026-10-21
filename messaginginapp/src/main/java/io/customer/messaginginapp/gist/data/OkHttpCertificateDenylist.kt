package io.customer.messaginginapp.gist.data

import io.customer.sdk.core.network.DenylistHostnameVerifier
import okhttp3.OkHttpClient

/**
 * Returns a client that rejects TLS chains containing a certificate on the SDK's certificate
 * denylist. It wraps this client's own hostname verifier and shares its connection pool,
 * dispatcher, cache, and interceptors.
 */
internal fun OkHttpClient.withCertificateDenylist(): OkHttpClient =
    if (hostnameVerifier is DenylistHostnameVerifier) {
        this
    } else {
        // Rebuild chains with the client's own trust manager, so they see the same cached
        // intermediates as its handshakes.
        val trustManager = x509TrustManager
        val verifier = if (trustManager != null) {
            DenylistHostnameVerifier(hostnameVerifier, trustManager)
        } else {
            DenylistHostnameVerifier(hostnameVerifier)
        }
        newBuilder().hostnameVerifier(verifier).build()
    }
