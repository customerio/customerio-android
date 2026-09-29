package io.customer.sdk.core.network

import io.customer.base.internal.InternalCustomerIOApi
import java.io.IOException

/**
 * A non-2xx HTTP response, carrying the status a caller needs to tell "try again later" apart from
 * "this will never be accepted".
 *
 * Extends [IOException] so the existing `it is IOException` retry predicates keep classifying it the
 * way they always have; only callers that opt in to reading [statusCode] behave differently.
 */
@InternalCustomerIOApi
class HttpRequestFailure(
    val statusCode: Int,
    val responseBody: String
) : IOException("HTTP $statusCode: $responseBody") {
    /**
     * Whether retrying the identical request could plausibly succeed.
     *
     * 408 and 429 are explicit invitations to retry, and 5xx is the server failing rather than
     * refusing. Every other 4xx is a rejection of this payload: retrying it forever only blocks
     * whatever is queued behind it.
     */
    val isRetryable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
}
