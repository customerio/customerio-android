package io.customer.base.error

open class ErrorDetail(
    val message: String? = null,
    val statusCode: StatusCode = StatusCode.Unknown,
    val cause: Throwable = Throwable()
) {
    fun getDisplayMessage(): String {
        if (message != null) {
            return message
        }
        if (statusCode != StatusCode.Unknown) {
            return statusCode.getMessage()
        }
        return cause.message ?: statusCode.getMessage()
    }
}
