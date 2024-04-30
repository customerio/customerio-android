package io.customer.core.environment

import io.customer.android.core.BuildConfig

/**
 * Wrapper class to hold static/only one time defined properties from
 * build environment and other Android classes to achieve the following:
 * - making it easier to test classes relying on these properties
 * - create abstraction and reduce dependency from native Android properties;
 * can be helpful in SDK wrappers.
 */
interface BuildEnvironment {
    val debugModeEnabled: Boolean
}

class DefaultBuildEnvironment : BuildEnvironment {
    override val debugModeEnabled: Boolean = BuildConfig.DEBUG
}
