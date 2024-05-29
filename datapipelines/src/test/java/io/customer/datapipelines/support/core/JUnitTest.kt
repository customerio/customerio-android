package io.customer.datapipelines.support.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Extension of the [UnitTest] class to provide setup and teardown methods for
 * JUnit tests using JUnit 5 annotations to setup and teardown the test environment.
 * Thr class uses test application instance to allow running tests without depending
 * on Android context and resources.
 */
open class JUnitTest : UnitTest() {
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
