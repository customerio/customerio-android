package io.customer.sdk.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.extenstions.add
import io.customer.base.extenstions.hasPassed
import io.customer.base.extenstions.subtract
import io.customer.commontest.BaseTest
import io.customer.commontest.extensions.toResponseBody
import io.customer.sdk.error.CustomerIOApiErrorResponse
import io.customer.sdk.error.CustomerIOApiErrorsResponse
import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.preference.SitePreferenceRepository
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class HttpRequestRunnerTest : BaseTest() {

    private lateinit var httpRunner: HttpRequestRunnerImpl

    private val retryPolicyMock: HttpRetryPolicy = mock()
    private val prefsRepository: SitePreferenceRepository // use real instance as mocking can take more work and error-prone test functions
        get() = di.sitePreferenceRepository

    @Before
    override fun setup() {
        super.setup()

        httpRunner = HttpRequestRunnerImpl(prefsRepository, di.logger, retryPolicyMock, jsonAdapter)
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
    fun performHttpRequest_givenHttpRequestsArePaused_expectDoNotMakeCall_expectReturnFail(): Unit =
        runBlocking {
            prefsRepository.httpRequestsPauseEnds = Date().add(1, TimeUnit.MINUTES)
            val httpClientMock = HttpClientMock<Unit>(emptyList())

            val actual = httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            actual.isFailure.shouldBeTrue()
            (actual.exceptionOrNull() is CustomerIOError.HttpRequestsPaused).shouldBeTrue()

            httpClientMock.didPerformRequest.shouldBeFalse()
            verifyNoInteractions(retryPolicyMock)
            assertHttpRequestsPaused()
        }

    @Test
    fun performHttpRequest_givenHttpRequestsPauseEnded_expectMakeRequest(): Unit = runBlocking {
        prefsRepository.httpRequestsPauseEnds = Date().subtract(1, TimeUnit.MINUTES)
        val httpClientMock = HttpClientMock(Response.success(Unit))

        httpRunner.performAndProcessRequest {
            httpClientMock.performRequest()
        }

        httpClientMock.didPerformRequest.shouldBeTrue()
    }

    @Test
    fun performHttpRequest_givenInternetConnectionBad_expectFailure(): Unit = runBlocking {
        val actual = httpRunner.performAndProcessRequest<Unit> {
            throw IOException("No internet connection")
        }

        (actual.exceptionOrNull() is CustomerIOError.NoHttpRequestMade).shouldBeTrue()
    }

    @Test
    fun performHttpRequest_givenSuccessfulRequest_expectResetToPrepareForNextRequest(): Unit =
        runBlocking {
            val httpClientMock = HttpClientMock(Response.success(Unit))

            httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            assertPrepareForNextRelease()
            assertHttpRequestsPaused(false)
        }

    @Test
    fun performHttpRequest_givenSuccessfulRequest_expectReturnResultFromResponse(): Unit =
        runBlocking {
            data class MockResponseBody(val foo: String = String.random)

            val expectedBody = MockResponseBody()
            val expected = Result.success(expectedBody)
            val httpClientMock = HttpClientMock(Response.success(expectedBody))

            val actual = httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            expected shouldBeEqualTo actual
            assertHttpRequestsPaused(false)
        }

    @Test
    fun performHttpRequest_given500_givenRetryPolicyRanOutOfTime_expectPauseHttpRequests_expectPrepareForNextRelease_expectReturnFailure(): Unit =
        runBlocking {
            whenever(retryPolicyMock.nextSleepTime).thenReturn(null)
            val httpClientMock = HttpClientMock(Response.error<Unit>(500, "".toResponseBody()))

            val actual = httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            assertHttpRequestsPaused()
            assertPrepareForNextRelease()
            (actual.exceptionOrNull() is CustomerIOError.ServerDown).shouldBeTrue()
        }

    /**
     * This test is commented out because it's throwing an exception:
     * kotlinx.coroutines.JobCancellationException: DeferredCoroutine was cancelled
     *
     * There is a try/catch block when calling .cancel() but the excepetion is still throwing and failing the test.
     * When get the time, fix this test. Until then, try to QA test the 500 path to see if the SDK throws an exception at runtime (not good).
     */
//    @Test
//    fun performHttpRequest_given500_givenRetryThenSuccess_expectDoNotPauseHttpRequests_expectPrepareForNextRelease_expectReturnSuccessfulResult(): Unit = runBlocking {
//        val expected = Result.success(Unit)
//        whenever(retryPolicyMock.nextSleepTime).thenReturn(Seconds.testValue())
//        val httpClientMock = HttpClientMock(listOf(
//            Response.error(500, "".toResponseBody()),
//            Response.success(Unit)
//        ))
//
//        val actual = httpRunner.performAndProcessRequest {
//            httpClientMock.performRequest()
//        }
//
//        httpClientMock.numberTimesPerformedRequest shouldBeEqualTo 2
//        verify(retryPolicyMock, times(1)).nextSleepTime
//        assertHttpRequestsPaused(false)
//        assertPrepareForNextRelease()
//        actual shouldBeEqualTo expected
//    }

    @Test
    fun performHttpRequest_given401_expectPauseHttpRequests_expectReturnFailure(): Unit =
        runBlocking {
            val httpClientMock = HttpClientMock<Unit>(Response.error(401, "".toResponseBody()))

            val actual = httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            assertHttpRequestsPaused()
            (actual.exceptionOrNull() is CustomerIOError.Unauthorized).shouldBeTrue()
        }

    @Test
    fun performHttpRequest_given4XX_expectReturnFailure(): Unit = runBlocking {
        val expectedApiError =
            CustomerIOApiErrorResponse(CustomerIOApiErrorResponse.Meta(String.random))
        val expected = Result.failure<Unit>(
            CustomerIOError.UnsuccessfulStatusCode(
                403,
                expectedApiError.throwable.message!!
            )
        )
        val httpClientMock =
            HttpClientMock(Response.error<Unit>(403, expectedApiError.toResponseBody(jsonAdapter)))

        val actual = httpRunner.performAndProcessRequest {
            httpClientMock.performRequest()
        }

        assertHttpRequestsPaused(false)
        actual shouldBeEqualTo expected
    }

    @Test
    fun performHttpRequest_given400_expectNotPausedHttpRequestsAndReturnFailure(): Unit =
        runBlocking {
            val httpClientMock = HttpClientMock<Unit>(Response.error(400, "".toResponseBody()))

            val actual = httpRunner.performAndProcessRequest {
                httpClientMock.performRequest()
            }

            assertHttpRequestsPaused(false)
            (actual.exceptionOrNull() is CustomerIOError.BadRequest400).shouldBeTrue()
        }

    @Test
    fun parseCustomerIOErrorBody_givenInvalidErrorBody_expectNull() {
        httpRunner.parseCustomerIOErrorBody(String.random).shouldBeNull()
    }

    @Test
    fun parseCustomerIOErrorBody_givenErrorBodies_expectParsedResultBack() {
        val errorResponse =
            CustomerIOApiErrorResponse(CustomerIOApiErrorResponse.Meta(String.random))
        val errorsResponse =
            CustomerIOApiErrorsResponse(CustomerIOApiErrorsResponse.Meta(listOf(String.random)))

        var expected = errorResponse.throwable.message
        var actual =
            httpRunner.parseCustomerIOErrorBody(jsonAdapter.toJson(errorResponse))!!.message

        expected shouldBeEqualTo actual

        expected = errorsResponse.throwable.message
        actual = httpRunner.parseCustomerIOErrorBody(jsonAdapter.toJson(errorsResponse))!!.message

        expected shouldBeEqualTo actual
    }

    // A HTTP pause for too long of a time is not good. Make sure we pause for an acceptable range.
    @Test
    fun pauseHttpRequests_assertDontPauseForTooLong() {
        httpRunner.pauseHttpRequests()

        val actual = prefsRepository.httpRequestsPauseEnds!!

        val minRange = Date().add(30, TimeUnit.SECONDS)
        val maxRange = Date().add(30, TimeUnit.MINUTES)

        (actual > minRange && actual < maxRange).shouldBeTrue()
    }

    class HttpClientMock<Body>(responses: List<Response<Body>>) {
        constructor(response: Response<Body>) : this(listOf(response))

        var responsesToReturn: MutableList<Response<Body>> = responses.toMutableList()

        var didPerformRequest = false
        var numberTimesPerformedRequest = 0

        fun performRequest(): Response<Body> {
            didPerformRequest = true
            numberTimesPerformedRequest += 1

            return responsesToReturn.first()
        }
    }
}
