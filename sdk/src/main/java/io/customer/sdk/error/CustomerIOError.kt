package io.customer.sdk.error

/**
 * Public facing errors that the CustomerIO SDK can create.
 */
internal sealed class CustomerIOError(message: String) : Throwable(message) {
    class Unauthorized : CustomerIOError("HTTP request responded with 401. Configure the SDK with valid credentials.")
    class HttpRequestsPaused : CustomerIOError("HTTP request skipped. All HTTP requests are paused for the time being.")
    class NoHttpRequestMade : CustomerIOError("HTTP request was not able to be made. It might be an Internet connection issue. Try again later.")
    class BadRequest : CustomerIOError("HTTP request responded with 400. The request body contains errors or is malformed.")
    class ServerDown : CustomerIOError("Customer.io API server unavailable. It's best to wait and try again later.")
    data class UnsuccessfulStatusCode(val code: Int, val apiMessage: String) : CustomerIOError("code: $code: $apiMessage")
}
