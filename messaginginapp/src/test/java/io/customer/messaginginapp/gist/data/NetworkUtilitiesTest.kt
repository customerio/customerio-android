package io.customer.messaginginapp.gist.data

import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.Client
import okhttp3.Request
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the header set shared by the queue fetch client and the SSE connect request,
 * the only two callers of [NetworkUtilities.addCommonHeaders].
 */
@RunWith(RobolectricTestRunner::class)
class NetworkUtilitiesTest : IntegrationTest() {

    private val hostPackageName = "com.example.test_app"

    private fun buildHeaders(includeUserToken: Boolean = true) = NetworkUtilities.addCommonHeaders(
        builder = Request.Builder().url("https://gist.example.com/api/v4/users"),
        state = InAppMessagingState(siteId = "site", dataCenter = "us"),
        includeUserToken = includeUserToken
    ).build().headers

    @Test
    fun addCommonHeaders_givenFetchRequest_expectAppIdentifierIsHostPackageName() {
        val headers = buildHeaders()

        headers[NetworkUtilities.CIO_APP_IDENTIFIER_HEADER] shouldBeEqualTo hostPackageName
    }

    @Test
    fun addCommonHeaders_givenSseRequest_expectAppIdentifierIsHostPackageName() {
        // SSE passes the user token in the URL instead of a header, but shares every other header.
        val headers = buildHeaders(includeUserToken = false)

        headers[NetworkUtilities.CIO_APP_IDENTIFIER_HEADER] shouldBeEqualTo hostPackageName
        headers[NetworkUtilities.USER_TOKEN_HEADER] shouldBeEqualTo null
    }

    @Test
    fun addCommonHeaders_givenWrapperSdkSource_expectAppIdentifierIsHostPackageName() {
        // Wrapper SDKs initialize the native SDK with the host Application, so the app identifier
        // is the host app's id even though the platform header reports the wrapper.
        SDKComponent.android().overrideDependency(Client(source = "ReactNative", sdkVersion = "1.2.3"))

        val headers = buildHeaders()

        headers[NetworkUtilities.CIO_APP_IDENTIFIER_HEADER] shouldBeEqualTo hostPackageName
        headers[NetworkUtilities.CIO_CLIENT_PLATFORM] shouldBeEqualTo "reactnative-android"
    }
}
