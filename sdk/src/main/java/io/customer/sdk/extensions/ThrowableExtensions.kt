package io.customer.sdk.extensions

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import retrofit2.HttpException

fun <T> Throwable.getErrorResult(): ErrorResult<T> {
    return ErrorResult(this.getErrorDetail())
}

fun Throwable.getErrorDetail(): ErrorDetail {
    return if (this is HttpException) {
        val statusCode = StatusCode.values().find { it.code == this.code() }
            ?: StatusCode.Unknown
        ErrorDetail(
            message = statusCode.getMessage(),
            statusCode = statusCode,
            cause = this
        )
    } else {
        ErrorDetail(cause = this)
    }
}
