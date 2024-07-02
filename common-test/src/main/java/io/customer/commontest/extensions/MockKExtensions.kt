package io.customer.commontest.extensions

import io.mockk.Called
import io.mockk.MockKVerificationScope
import io.mockk.verify

/**
 * Extension for MockK verify function to verify a block of code is called exactly once.
 */
fun verifyOnce(
    verifyBlock: MockKVerificationScope.() -> Unit
) = verify(exactly = 1, verifyBlock = verifyBlock)

/**
 * Extension for MockK verify function to verify a block of code is called was never called.
 */
fun verifyNever(
    verifyBlock: MockKVerificationScope.() -> Unit
) = verify(exactly = 0, verifyBlock = verifyBlock)

/**
 * Extension for MockK verify function to verify that no interactions were made with provided mocks.
 */
fun verifyNoInteractions(
    vararg mocks: Any
) = verify { mocks.toList() wasNot Called }
