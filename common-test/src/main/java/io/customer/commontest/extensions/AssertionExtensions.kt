package io.customer.commontest.extensions

/**
 * Extension function to assert that Result is failure and the exception is of the specified type.
 */
inline fun <reified E : Throwable> Result<Any>.shouldBeFailure(): E {
    if (!isFailure) {
        throw AssertionError("Expected failure but the result was successful")
    }

    val exception = exceptionOrNull()
    if (exception == null) {
        throw AssertionError("Expected exception of type ${E::class} but was null")
    } else if (exception !is E) {
        throw AssertionError("Expected exception of type ${E::class} but was ${exception::class}")
    }

    return exception
}
