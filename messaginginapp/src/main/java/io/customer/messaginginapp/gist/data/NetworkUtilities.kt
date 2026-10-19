package io.customer.messaginginapp.gist.data

import android.util.Base64
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import java.io.IOException
import okhttp3.Request

class NetworkUtilities {
    companion object {
        internal const val CIO_SITE_ID_HEADER = "X-CIO-Site-Id"
        internal const val USER_TOKEN_HEADER = "X-Gist-Encoded-User-Token"
        internal const val CIO_DATACENTER_HEADER = "X-CIO-Datacenter"
        internal const val CIO_CLIENT_PLATFORM = "X-CIO-Client-Platform"
        internal const val CIO_CLIENT_VERSION = "X-CIO-Client-Version"
        internal const val CIO_CLIENT_APP_IDENTIFIER = "X-CIO-Client-App-Identifier"
        internal const val GIST_USER_ANONYMOUS_HEADER = "X-Gist-User-Anonymous"
        internal const val SDK_NOT_INITIALIZED_MESSAGE =
            "Customer.io SDK is not initialized or was reset; in-app request aborted"

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
         * @throws IOException if the SDK graph is unavailable. OkHttp reports this to the call's
         * callback without rethrowing an unchecked exception on its dispatcher thread.
         */
        @Throws(IOException::class)
        internal fun addCommonHeaders(
            builder: Request.Builder,
            state: InAppMessagingState,
            includeUserToken: Boolean = true
        ): Request.Builder {
            val androidSDKComponent = SDKComponent.getOrNull<AndroidSDKComponent>()
                ?: throw IOException(SDK_NOT_INITIALIZED_MESSAGE)
            builder.addHeader(CIO_SITE_ID_HEADER, state.siteId)
            builder.addHeader(CIO_DATACENTER_HEADER, state.dataCenter)

            builder.addHeader(CIO_CLIENT_PLATFORM, androidSDKComponent.client.source.lowercase() + "-android")
            builder.addHeader(CIO_CLIENT_VERSION, androidSDKComponent.client.sdkVersion)
            builder.addHeader(CIO_CLIENT_APP_IDENTIFIER, androidSDKComponent.applicationStore.customerPackageName)

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
