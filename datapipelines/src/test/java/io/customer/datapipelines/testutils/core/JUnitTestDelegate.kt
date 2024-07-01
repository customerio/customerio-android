package io.customer.datapipelines.testutils.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Extension of the [UnitTestDelegate] class to provide setup and teardown methods for
 * JUnit tests using JUnit 5 annotations to setup and teardown the test environment.
 * Thr class uses test application instance to allow running tests without depending
 * on Android context and resources.
 */
open class JUnitTestDelegate : UnitTestDelegate() {
    override var testApplication: Any = "Test"

    @BeforeEach
    open fun setup() {
        setupTestEnvironment()
    }

    @AfterEach
    open fun teardown() {
        deinitializeModule()
    }
}
