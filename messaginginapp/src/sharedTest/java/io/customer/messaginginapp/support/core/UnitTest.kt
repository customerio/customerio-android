package io.customer.messaginginapp.support.core

import io.customer.sdk.core.di.SDKComponent

abstract class UnitTest {
    protected open fun setupTestEnvironment() {
        setupSDKComponent()
    }

    protected open fun setupSDKComponent() {
    }

    protected open fun deinitializeModule() {
        SDKComponent.reset()
    }
}
