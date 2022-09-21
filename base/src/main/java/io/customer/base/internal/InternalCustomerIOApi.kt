package io.customer.base.internal

@RequiresOptIn(
    message = "This is internal API for CustomerIO. Do not depend on this API in your own client code.",
    level = RequiresOptIn.Level.ERROR
)
annotation class InternalCustomerIOApi
