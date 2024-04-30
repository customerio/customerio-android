package io.customer.sdk.api

import io.customer.base.extenstions.add
import io.customer.base.extenstions.hasPassed
import io.customer.sdk.core.util.Logger
import io.customer.sdk.error.CustomerIOApiErrorResponse
import io.customer.sdk.error.CustomerIOApiErrorsResponse
import io.customer.sdk.error.CustomerIOError
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.util.JsonAdapter
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import retrofit2.Response

internal interface HttpRequestRunner {
    suspend fun <R> performAndProcessRequest(makeRequest: suspend () -> Response<R>): Result<R>
}

/**
 * Where HTTP response processing occurs.
 */
internal class HttpRequestRunnerImpl(
    private val prefsRepository: SitePreferenceRepository,
    private val logger: Logger,
    private val retryPolicy: HttpRetryPolicy,
    private val jsonAdapter: JsonAdapter
) : HttpRequestRunner {

    override suspend fun <R> performAndProcessRequest(makeRequest: suspend () -> Response<R>): Result<R> {
        prefsRepository.httpRequestsPauseEnds?.let { httpPauseEnds ->
            if (!httpPauseEnds.hasPassed()) {
                logger.debug("HTTP request ignored because requests are still paused.")
                return Result.failure(CustomerIOError.HttpRequestsPaused())
            }
        }

        var response: Response<R>? = null
        try {
            response = makeRequest()
        } catch (e: Throwable) {
            // HTTP request was not able to be made. Probably an Internet connection issue
            logger.debug("HTTP request failed. Error: ${e.message}")
        }

        if (response == null) {
            return Result.failure(CustomerIOError.NoHttpRequestMade())
        }

        val responseBody = response.body()
        if (response.isSuccessful && responseBody != null) {
            prepareForNextRequest() // after successful HTTP request, reset to prepare for the next HTTP request

            return Result.success(responseBody)
        }

        return processUnsuccessfulResponse(response, makeRequest)
    }

    suspend fun <R> processUnsuccessfulResponse(
        response: Response<R>,
        makeRequest: suspend () -> Response<R>
    ): Result<R> {
        // Note: calling .string(), you are not able to get the error body again. retrofit clears the error body after calling .string()
        // That's why we get the value here for the whole function body to use.
        val httpResponseErrorBodyString = response.errorBody()?.string()

        // parse the server response for use later.
        // First, try to get a parsed version of the HTTP response body. Then, use the raw JSON response. If that also fails, return a generic default message to the customer.
        val parsedCustomerIOServerResponse = parseCustomerIOErrorBody(httpResponseErrorBodyString)?.message ?: httpResponseErrorBodyString ?: "(server did not give a response)"

        when (val statusCode = response.code()) {
            in 500 until 600 -> {
                val sleepTime = retryPolicy.nextSleepTime
                return if (sleepTime != null) {
                    logger.debug("Encountered $statusCode HTTP response. Sleeping $sleepTime seconds and then retrying.")

                    delay(sleepTime.toMilliseconds.value)

                    this.performAndProcessRequest(makeRequest)
                } else {
                    pauseHttpRequests()
                    prepareForNextRequest() // after retry policy is finished, reset to prepare for the next HTTP request

                    Result.failure(CustomerIOError.ServerDown())
                }
            }
            401 -> {
                pauseHttpRequests()

                return Result.failure(CustomerIOError.Unauthorized())
            }
            400 -> {
                return Result.failure(CustomerIOError.BadRequest400(parsedCustomerIOServerResponse))
            }
            else -> {
                val customerIOError = CustomerIOError.UnsuccessfulStatusCode(statusCode, parsedCustomerIOServerResponse)

                logger.error("4xx HTTP status code response. Probably a bug? $parsedCustomerIOServerResponse")

                return Result.failure(customerIOError)
            }
        }
    }

    internal fun parseCustomerIOErrorBody(errorBody: String?): Throwable? {
        if (errorBody == null) return null

        return jsonAdapter.fromJsonOrNull<CustomerIOApiErrorResponse>(errorBody)?.throwable
            ?: jsonAdapter.fromJsonOrNull<CustomerIOApiErrorsResponse>(errorBody)?.throwable
    }

    private fun prepareForNextRequest() {
        retryPolicy.reset() // in case retry policy was used, reset it so the next request can use it.
        // do not edit the HTTP pausing. Let that get modified somewhere else and get reset by timing out.
    }

    // In certain scenarios, it makes sense for us to pause making any HTTP requests to the
    // Customer.io API. Because HTTP requests are performed by the background queue, there is
    // a chance that the background queue could make a lot of HTTP requests in
    // a short amount of time from lots of devices. This makes a performance impact on our API.
    // By pausing HTTP requests, we mitigate the chance of customer devices causing harm to our API.
    internal fun pauseHttpRequests() {
        val minutesToPause = 5
        logger.info("All HTTP requests to Customer.io API have been paused for $minutesToPause minutes")

        val dateToEndPause = Date().add(minutesToPause, TimeUnit.MINUTES)

        prefsRepository.httpRequestsPauseEnds = dateToEndPause
    }
}
