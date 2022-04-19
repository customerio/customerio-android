package io.customer.sdk.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.add
import io.customer.base.extenstions.hasPassed
import io.customer.base.extenstions.subtract
import io.customer.common_test.BaseTest
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.error.CustomerIOApiErrorResponse
import io.customer.sdk.error.CustomerIOApiErrorsResponse
import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Seconds
import io.customer.sdk.utils.random
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.util.*
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BaseHttpClientTest : BaseTest() {

    /**
     * To test the abstract [BaseHttpClient], use a random subclass for the testing.
     */
    private lateinit var httpClient: RetrofitTrackingHttpClient

    private val serviceMock: CustomerIOService = mock()
    private val retryPolicyMock: HttpRetryPolicy = mock()
    private val prefsRepository: PreferenceRepository // use real instance as mocking can take more work and error-prone test functions
        get() = di.sharedPreferenceRepository

    @Before
    override fun setup() {
        super.setup()

        httpClient = RetrofitTrackingHttpClient(serviceMock, di.logger, retryPolicyMock, prefsRepository, di.timer)
    }

    private fun assertHttpRequestsPaused(shouldHavePaused: Boolean = true) {
        if (shouldHavePaused) {
            val actualTimeoutHttpPause = prefsRepository.httpRequestsPauseEnds
            actualTimeoutHttpPause.shouldNotBeNull()
            actualTimeoutHttpPause.hasPassed().shouldBeFalse() // time should be made in the future.
        } else {
            prefsRepository.httpRequestsPauseEnds.shouldBeNull()
        }
    }

    private fun assertPrepareForNextRelease() {
        verify(retryPolicyMock).reset()
    }

    @Test
    fun performAndProcessRequest_givenHttpRequestsArePaused_expectDoNotMakeCall_expectReturnFail(): Unit = runBlocking {
        prefsRepository.httpRequestsPauseEnds = Date().add(1, TimeUnit.MINUTES)

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        actual.isFailure.shouldBeTrue()
        actual.exceptionOrNull().shouldBeEqualTo(CustomerIOError.HttpRequestsPaused())

        verifyNoInteractions(serviceMock)
        verifyNoInteractions(retryPolicyMock)
        assertHttpRequestsPaused()
    }

    @Test
    fun performAndProcessRequest_givenHttpRequestsPauseEnded_expectMakeRequest(): Unit = runBlocking {
        prefsRepository.httpRequestsPauseEnds = Date().subtract(1, TimeUnit.MINUTES)
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.success(Unit))
        val givenIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)

        httpClient.identifyProfile(givenIdentifier, givenAttributes)

        verify(serviceMock).identifyCustomer(givenIdentifier, givenAttributes)
    }

    @Test
    fun performAndProcessRequest_givenSuccessfulRequest_expectResetToPrepareForNextRequest(): Unit = runBlocking {
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.success(Unit))

        httpClient.identifyProfile(String.random, emptyMap())

        assertPrepareForNextRelease()
        assertHttpRequestsPaused(false)
    }

    @Test
    fun performAndProcessRequest_givenSuccessfulRequest_expectReturnResultFromResponse(): Unit = runBlocking {
        // Test not very useful at the moment where all HTTP requests made by the SDK have a Unit response. But, still a use case important to test for.
        val expected = Result.success(Unit)
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.success(Unit))

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        expected shouldBeEqualTo actual
        assertHttpRequestsPaused(false)
    }

    @Test
    fun performAndProcessRequest_given500_givenRetryPolicyRanOutOfTime_expectPauseHttpRequests_expectPrepareForNextRelease_expectReturnFailure(): Unit = runBlocking {
        whenever(retryPolicyMock.nextSleepTime).thenReturn(null)
        val expected = Result.failure<Unit>(CustomerIOError.ServerDown())
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.success(500, null))

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        assertHttpRequestsPaused()
        assertPrepareForNextRelease()
        actual shouldBeEqualTo expected
    }

    @Test
    fun performAndProcessRequest_given500_givenRetryThenSuccess_expectDoNotPauseHttpRequests_expectPrepareForNextRelease_expectReturnSuccessfulResult(): Unit = runBlocking {
        val expected = Result.success(Unit)
        val sleepTimes = mutableListOf(Seconds(0.1))
        whenever(retryPolicyMock.nextSleepTime).thenAnswer { sleepTimes.removeFirstOrNull() }
        val responses = mutableListOf<Response<Unit>>(
            Response.success(500, null),
            Response.success(Unit)
        )
        whenever(serviceMock.identifyCustomer(any(), any())).thenAnswer { responses.removeFirstOrNull() }

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        verify(serviceMock, times(2)).identifyCustomer(any(), any())
        assertHttpRequestsPaused(false)
        assertPrepareForNextRelease()
        actual shouldBeEqualTo expected
    }

    @Test
    fun performAndProcessRequest_given401_expectPauseHttpRequests_expectResetToPrepareForNextRequest_expectReturnFailure(): Unit = runBlocking {
        val expected = Result.failure<Unit>(CustomerIOError.Unauthorized())
        prefsRepository.httpRequestsPauseEnds.shouldBeNull()
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.success(401, null))

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        assertHttpRequestsPaused()
        assertPrepareForNextRelease()
        actual shouldBeEqualTo expected
    }

    @Test
    fun performAndProcessRequest_given4XX_expectReturnFailure(): Unit = runBlocking {
        val expectedApiError = CustomerIOApiErrorResponse(CustomerIOApiErrorResponse.Meta(String.random))
        val expected = Result.failure<Unit>(CustomerIOError.UnsuccessfulStatusCode(403, expectedApiError.message!!))
        whenever(serviceMock.identifyCustomer(any(), any())).thenReturn(Response.error(403, ResponseBody))

        val actual = httpClient.identifyProfile(String.random, emptyMap())

        assertHttpRequestsPaused()
        assertPrepareForNextRelease()
        actual shouldBeEqualTo expected
    }

    @Test
    fun parseCustomerIOErrorBody_givenInvalidErrorBody_expectNull() {
        httpClient.parseCustomerIOErrorBody(String.random).shouldBeNull()
    }

    @Test
    fun parseCustomerIOErrorBody_givenErrorBodies_expectParsedResultBack() {
        val jsonAdapter = di.jsonAdapter

        val errorResponse = CustomerIOApiErrorResponse(CustomerIOApiErrorResponse.Meta(String.random))
        val errorsResponse = CustomerIOApiErrorsResponse(CustomerIOApiErrorsResponse.Meta(listOf(String.random)))

        var expected = errorResponse.message
        var actual = httpClient.parseCustomerIOErrorBody(jsonAdapter.toJson(errorResponse))!!.message

        expected shouldBeEqualTo actual

        expected = errorsResponse.message
        actual = httpClient.parseCustomerIOErrorBody(jsonAdapter.toJson(errorsResponse))!!.message

        expected shouldBeEqualTo actual
    }

    // A HTTP pause for too long of a time is not good. Make sure we pause for an acceptable range.
    @Test
    fun pauseHttpRequests_assertDontPauseForTooLong() {
        httpClient.pauseHttpRequests()

        val actual = prefsRepository.httpRequestsPauseEnds!!

        val minRange = Date().add(30, TimeUnit.SECONDS)
        val maxRange = Date().add(30, TimeUnit.MINUTES)

        (actual > minRange && actual < maxRange).shouldBeTrue()
    }
}
