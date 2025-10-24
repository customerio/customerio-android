package io.customer.messaginginapp.gist.data

import android.util.Base64
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.SDKComponent
import okhttp3.Request

class NetworkUtilities {
    companion object {
        internal const val CIO_SITE_ID_HEADER = "X-CIO-Site-Id"
        internal const val USER_TOKEN_HEADER = "X-Gist-Encoded-User-Token"
        internal const val CIO_DATACENTER_HEADER = "X-CIO-Datacenter"
        internal const val CIO_CLIENT_PLATFORM = "X-CIO-Client-Platform"
        internal const val CIO_CLIENT_VERSION = "X-CIO-Client-Version"
        internal const val GIST_USER_ANONYMOUS_HEADER = "X-Gist-User-Anonymous"

        // SSE-specific headers
        internal const val SSE_ACCEPT_HEADER = "Accept"
        internal const val SSE_ACCEPT_VALUE = "text/event-stream"
        internal const val SSE_CACHE_CONTROL_HEADER = "Cache-Control"
        internal const val SSE_CACHE_CONTROL_VALUE = "no-cache"

        // SSE query parameters
        internal const val SSE_SESSION_ID_PARAM = "sessionId"
        internal const val SSE_SITE_ID_PARAM = "siteId"
        internal const val SSE_USER_TOKEN_PARAM = "userToken"

        // SSE configuration
        internal const val SSE_READ_TIMEOUT_SECONDS = 300L

        // Heartbeat timer configuration
        internal const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 30000L // 30 seconds default
        internal const val HEARTBEAT_BUFFER_MS = 5000L // 5 additional seconds to account for network latency

        /**
         * Creates common headers for Customer.io API requests.
         *
         * @param builder Request.Builder to add headers to
         * @param state Current in-app messaging state
         * @param includeUserToken Whether to include user token header (default: true)
         * @return Request.Builder with common headers applied
         */
        internal fun addCommonHeaders(
            builder: Request.Builder,
            state: InAppMessagingState,
            includeUserToken: Boolean = true
        ): Request.Builder {
            builder.addHeader(CIO_SITE_ID_HEADER, state.siteId)
            builder.addHeader(CIO_DATACENTER_HEADER, state.dataCenter)
            builder.addHeader(CIO_CLIENT_PLATFORM, SDKComponent.android().client.source.lowercase() + "-android")
            builder.addHeader(CIO_CLIENT_VERSION, SDKComponent.android().client.sdkVersion)
            builder.addHeader(GIST_USER_ANONYMOUS_HEADER, (state.userId == null).toString())

            if (includeUserToken) {
                val userToken = state.userId ?: state.anonymousId
                userToken?.let { token ->
                    builder.addHeader(
                        USER_TOKEN_HEADER,
                        Base64.encodeToString(token.toByteArray(), Base64.NO_WRAP)
                    )
                }
            }

            return builder
        }
    }
}
