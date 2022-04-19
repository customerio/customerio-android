package io.customer.sdk.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.api.service.CustomerIOService
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class TrackingHttpClientTest : BaseTest() {

    private lateinit var httpClient: RetrofitTrackingHttpClient

    private val serviceMock: CustomerIOService = mock()
    private val retryPolicyMock: HttpRetryPolicy = mock()

    @Before
    override fun setup() {
        super.setup()

        httpClient = RetrofitTrackingHttpClient(serviceMock, di.logger, retryPolicyMock, di.sharedPreferenceRepository, di.timer)
    }

    /**
     * Tests are not meant to test all possible scenarios of [BaseHttpClient] (there are tests for that class already).
     * We do, however, want to test that each function is running the response processing code from [BaseHttpClient].
     * Doesn't matter the method as long as we are confident that we didn't forget to do call a [BaseHttpClient] function.
     */

    // TODO write tests
}
