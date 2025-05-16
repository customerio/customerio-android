package io.customer.commontest.extensions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * Flushes all pending coroutines in the test scope to ensure all events are processed
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T : Any> T.flushCoroutines(scope: TestScope): T {
    scope.runCurrent()
    return this
}
