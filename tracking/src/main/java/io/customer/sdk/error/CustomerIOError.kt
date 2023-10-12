package io.customer.sdk.error

/**
 * Public facing errors that the CustomerIO SDK can create.
 */
internal sealed class CustomerIOError(message: String) : Throwable(message) {
    class Unauthorized : CustomerIOError("HTTP request responded with 401. Configure the SDK with valid credentials.")
    class HttpRequestsPaused : CustomerIOError("HTTP request skipped. All HTTP requests are paused for the time being.")
    class NoHttpRequestMade : CustomerIOError("HTTP request was not able to be made. It might be an Internet connection issue. Try again later.")
    class BadRequest400(messageFromServer: String) : CustomerIOError("HTTP request responded with 400 - $messageFromServer")
    class ServerDown : CustomerIOError("Customer.io API server unavailable. It's best to wait and try again later.")
    data class UnsuccessfulStatusCode(val code: Int, val apiMessage: String) : CustomerIOError("Customer.io API server response - code: $code, error message: $apiMessage")
}
