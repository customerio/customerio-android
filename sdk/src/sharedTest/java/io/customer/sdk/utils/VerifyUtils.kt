package io.customer.sdk.testutils

import io.customer.base.data.ErrorResult
import io.customer.base.data.Result
import io.customer.base.error.StatusCode
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue

internal fun <T : Any> verifyError(result: Result<T>, statusCode: StatusCode) {
    result.isSuccess.shouldBeFalse()
    val errorResult = result as? ErrorResult
    errorResult?.error?.statusCode shouldBeEqualTo statusCode
}

internal fun <T : Any> verifySuccess(result: Result<T>, expected: T) {
    result.isSuccess.shouldBeTrue()
    result.get() shouldBeEqualTo expected
}
