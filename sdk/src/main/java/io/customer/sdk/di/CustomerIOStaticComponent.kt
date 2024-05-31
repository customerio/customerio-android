package io.customer.sdk.di

import io.customer.sdk.core.di.DiGraph
import io.customer.sdk.core.environment.BuildEnvironment
import io.customer.sdk.core.environment.DefaultBuildEnvironment
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.LogcatLogger
import io.customer.sdk.core.util.Logger
import io.customer.sdk.core.util.SdkDispatchers

/**
 * Static/shared component dependency graph to satisfy independent dependencies
 * from single place. All other graphs should never redefine dependencies defined
 * here unless extremely necessary.
 * <p/>
 * The class should only contain dependencies matching the following criteria:
 * - dependencies that may be required without SDK initialization
 * - dependencies that are lightweight and are not dependent on SDK initialization
 */
@Suppress("MemberVisibilityCanBePrivate")
class CustomerIOStaticComponent : DiGraph() {
    val buildEnvironment: BuildEnvironment by lazy {
        override() ?: DefaultBuildEnvironment()
    }

    val logger: Logger by lazy {
        override() ?: LogcatLogger(buildEnvironment = buildEnvironment)
    }

    val dispatchersProvider: DispatchersProvider by lazy { override() ?: SdkDispatchers() }
}
