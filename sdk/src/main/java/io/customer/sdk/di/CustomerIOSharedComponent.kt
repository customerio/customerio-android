package io.customer.sdk.di

import io.customer.sdk.util.DispatchersProvider
import io.customer.sdk.util.LogcatLogger
import io.customer.sdk.util.Logger
import io.customer.sdk.util.SdkDispatchers

class CustomerIOSharedComponent : DiGraph() {
    val logger: Logger
        get() = override() ?: getSingletonInstanceCreate { LogcatLogger() }

    val dispatchersProvider: DispatchersProvider
        get() = override() ?: SdkDispatchers()
}
