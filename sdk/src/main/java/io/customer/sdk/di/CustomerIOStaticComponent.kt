package io.customer.sdk.di

import io.customer.sdk.util.*

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
    val staticSettingsProvider: StaticSettingsProvider by lazy {
        override() ?: StaticSettingsProviderImpl()
    }

    val logger: Logger by lazy {
        override() ?: LogcatLogger(staticSettingsProvider = staticSettingsProvider)
    }

    val dispatchersProvider: DispatchersProvider by lazy { override() ?: SdkDispatchers() }
}
