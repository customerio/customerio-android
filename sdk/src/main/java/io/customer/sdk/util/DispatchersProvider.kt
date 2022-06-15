package io.customer.sdk.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Allows injecting dispatchers in automated tests
interface DispatchersProvider {
    val background: CoroutineDispatcher
    val main: CoroutineDispatcher
}

class SdkDispatchers : DispatchersProvider {
    override val background: CoroutineDispatcher
        get() = Dispatchers.IO

    override val main: CoroutineDispatcher
        get() = Dispatchers.Main
}
