package io.customer.messagingpush.testutils.core

import io.customer.commontest.util.UnitTestLogger
import io.customer.messagingpush.testutils.UnitTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

open class JUnitTest : UnitTest() {
    @BeforeEach
    open fun setup() {
        setupTestEnvironment()
    }

    @AfterEach
    open fun teardown() {
        deinitializeModule()
    }

    override fun setupSDKComponent() {
        super.setupSDKComponent()
        // Override logger dependency with test logger so logs can be captured in tests
        // This also makes logger independent of Android Logcat
        SDKComponent.overrideDependency(Logger::class.java, UnitTestLogger())
    }
}
