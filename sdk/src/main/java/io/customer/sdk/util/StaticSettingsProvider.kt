package io.customer.sdk.util

import io.customer.sdk.BuildConfig

/**
 * Wrapper class to hold static/only one time defined properties from
 * [BuildConfig] and other Android classes to achieve the following:
 * - making it easier to test classes relying on these properties
 * - create abstraction and reduce dependency from native Android properties;
 * can be helpful in SDK wrappers
 */
interface StaticSettingsProvider {
    val isDebuggable: Boolean
}

class StaticSettingsProviderImpl : StaticSettingsProvider {
    override val isDebuggable: Boolean = BuildConfig.DEBUG
}
