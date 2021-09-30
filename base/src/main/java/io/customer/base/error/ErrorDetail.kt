package io.customer.base.error

public open class ErrorDetail(
    public val message: String? = null,
    public val cause: Throwable = Throwable()
)
