package io.customer.commontest.extensions

import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.sdk.core.di.SDKComponent

/**
 * Registers the module in SDKComponent so it can be reused by DiGraph when needed.
 */
fun <T : CustomerIOGenericModule> T.register(module: T): T = module.apply {
    SDKComponent.modules[moduleName] = this
}
