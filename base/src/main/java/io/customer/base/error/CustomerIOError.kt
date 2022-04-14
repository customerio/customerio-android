package io.customer.base.error

sealed class CustomerIOError(message: String) : Throwable(message) {
    class Unauthorized : CustomerIOError("HTTP request responded with 401. Configure the SDK with valid credentials.")
    class HttpRequestsPaused : CustomerIOError("HTTP request skipped. All HTTP requests are paused for the time being.")
    class ServerDown : CustomerIOError("Customer.io API server unavailable. It's best to wait and try again later.")
}
