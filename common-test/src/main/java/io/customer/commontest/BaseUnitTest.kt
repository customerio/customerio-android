package io.customer.commontest

import org.junit.After
import org.junit.Before

/**
 * Base class for a unit test class to subclass.
 * Meant to provide convenience to test classes with properties and functions tests may use.
 *
 * If you want to create integration tests, use [BaseIntegrationTest].
 * If you want to create unit tests, use [BaseUnitTest].
 */
abstract class BaseUnitTest {
    @Before
    open fun setup() {
    }

    @After
    open fun teardown() {
    }
}
