package io.customer.commontest.extensions

import io.customer.commontest.core.BaseTest
import io.customer.commontest.module.CustomerIOGenericModule
import io.customer.sdk.core.di.SDKComponent

fun <T : CustomerIOGenericModule> BaseTest.registerModule(module: T): T = module.apply {
    SDKComponent.modules[moduleName] = this
}
