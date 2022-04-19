package io.customer.sdk.error

/**
 * Public facing errors that the CustomerIO SDK can create.
 */
sealed class CustomerIOError(message: String) : Throwable(message) {
    class Unauthorized : CustomerIOError("HTTP request responded with 401. Configure the SDK with valid credentials.")
    class HttpRequestsPaused : CustomerIOError("HTTP request skipped. All HTTP requests are paused for the time being.")
    class ServerDown : CustomerIOError("Customer.io API server unavailable. It's best to wait and try again later.")
    class UnsuccessfulStatusCode(code: Int, apiMessage: String) : CustomerIOError("code: $code: $apiMessage")
}
