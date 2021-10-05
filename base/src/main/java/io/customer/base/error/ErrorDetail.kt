package io.customer.base.error

open class ErrorDetail(
    val message: String? = null,
    val statusCode: StatusCode = StatusCode.Unknown,
    val cause: Throwable = Throwable()
)
