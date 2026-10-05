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

    // Spelled out rather than read from the constant: gist matches on the wire name, so a
    // test that reuses the constant would pass straight through a rename of its value.
    private val appIdentifierHeader = "X-CIO-Client-App-Identifier"

    private fun buildHeaders(
        includeUserToken: Boolean = true,
        includeAuthorization: Boolean = true,
        state: InAppMessagingState = InAppMessagingState(siteId = "site", dataCenter = "us")
    ) = NetworkUtilities.addCommonHeaders(
        builder = Request.Builder().url("https://gist.example.com/api/v4/users"),
        state = state,
        includeUserToken = includeUserToken,
        includeAuthorization = includeAuthorization
    ).build().headers

    private val publicKeyState = InAppMessagingState(siteId = "", dataCenter = "us", publicKey = "wk_us_key")

    @Test
    fun addCommonHeaders_givenSiteIdOnly_expectSiteIdHeaderAndNoAuthorization() {
        val headers = buildHeaders()

        headers["X-CIO-Site-Id"] shouldBeEqualTo "site"
        headers["Authorization"] shouldBeEqualTo null
    }

    @Test
    fun addCommonHeaders_givenPublicKeyWithoutSiteId_expectBearerAndNoSiteIdHeader() {
        val headers = buildHeaders(state = publicKeyState)

        headers["Authorization"] shouldBeEqualTo "Bearer wk_us_key"
        headers["X-CIO-Site-Id"] shouldBeEqualTo null
    }

    @Test
    fun addCommonHeaders_givenPublicKeyOnSseRequest_expectNoAuthorization() {
        // SSE passes the key in the URL instead
        val headers = buildHeaders(includeUserToken = false, includeAuthorization = false, state = publicKeyState)

        headers["Authorization"] shouldBeEqualTo null
    }

    @Test
    fun addCommonHeaders_givenFetchRequest_expectAppIdentifierIsHostPackageName() {
        val headers = buildHeaders()

        headers[appIdentifierHeader] shouldBeEqualTo hostPackageName
    }

    @Test
    fun addCommonHeaders_givenSseRequest_expectAppIdentifierIsHostPackageName() {
        // SSE passes the user token in the URL instead of a header, but shares every other header.
        val headers = buildHeaders(includeUserToken = false)

        headers[appIdentifierHeader] shouldBeEqualTo hostPackageName
        headers[NetworkUtilities.USER_TOKEN_HEADER] shouldBeEqualTo null
    }

    @Test
    fun addCommonHeaders_givenWrapperSdkSource_expectAppIdentifierIsHostPackageName() {
        // Wrapper SDKs initialize the native SDK with the host Application, so the app identifier
        // is the host app's id even though the platform header reports the wrapper.
        SDKComponent.android().overrideDependency(Client(source = "ReactNative", sdkVersion = "1.2.3"))

        val headers = buildHeaders()

        headers[appIdentifierHeader] shouldBeEqualTo hostPackageName
        headers[NetworkUtilities.CIO_CLIENT_PLATFORM] shouldBeEqualTo "reactnative-android"
    }
}
