package io.customer.commontest

import org.junit.After
import org.junit.Before

abstract class BaseUnitTest {
    @Before
    open fun setup() {
    }

    @After
    open fun teardown() {
    }
}
