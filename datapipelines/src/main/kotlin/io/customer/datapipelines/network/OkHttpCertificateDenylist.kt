package io.customer.datapipelines.network

import com.segment.analytics.kotlin.core.RequestFactory
import io.customer.sdk.core.network.DenylistHostnameVerifier
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol

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

/**
 * OkHttp client for Segment's analytics traffic with the certificate denylist applied. The
 * protocols and timeouts match analytics-kotlin's own default client, so only TLS verification
 * changes.
 */
internal fun createTrackingHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    .connectTimeout(TRACKING_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(TRACKING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()
    .withCertificateDenylist()

/** Request factory that sends Segment's settings and batch uploads through [createTrackingHttpClient]. */
internal fun createTrackingRequestFactory(): RequestFactory = RequestFactory(createTrackingHttpClient())

private const val TRACKING_CONNECT_TIMEOUT_SECONDS = 15L
private const val TRACKING_READ_TIMEOUT_SECONDS = 20L
