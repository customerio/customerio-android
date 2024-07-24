package io.customer.commontest.extensions

/**
 * Extension function to assert that Result is failure and the exception is of the specified type.
 */
inline fun <reified E : Throwable> Result<Any>.shouldBeFailure(): E {
    assertCondition(isFailure) {
        "Expected failure but the result was successful"
    }

    val exception = exceptionOrNull()
    return assertNotNull(exception as? E) {
        "Expected exception of type ${E::class} but was ${exception?.let { it::class } ?: "null"}"
    }
}

/**
 * Extension function to assert that Result is success and return the value.
 * If the result is failure, it will throw an AssertionError.
 */
inline fun assertCondition(condition: Boolean, lazyMessage: () -> String) {
    check(condition) {
        throw AssertionError(lazyMessage())
    }
}

/**
 * Extension function to assert that the value is not null and return it.
 * If the value is null, it will throw an AssertionError.
 */
inline fun <T : Any> assertNotNull(value: T?, lazyMessage: () -> String): T {
    check(value != null) {
        throw AssertionError(lazyMessage())
    }
    return value
}
