package io.customer.commontest.extensions

import io.mockk.Called
import io.mockk.MockKVerificationScope
import io.mockk.verify

fun verifyOnce(
    verifyBlock: MockKVerificationScope.() -> Unit
) = verify(
    exactly = 1,
    verifyBlock = verifyBlock
)

fun verifyNever(
    verifyBlock: MockKVerificationScope.() -> Unit
) = verify(
    exactly = 0,
    verifyBlock = verifyBlock
)

fun verifyNoInteractions(
    vararg mock: Any
) = verify {
    mock.toList() wasNot Called
}
