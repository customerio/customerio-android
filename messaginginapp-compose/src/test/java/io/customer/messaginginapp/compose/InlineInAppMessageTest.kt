package io.customer.messaginginapp.compose

import io.customer.sdk.core.di.SDKComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class InlineInAppMessageTest {
    @Before
    fun setUp() {
        SDKComponent.reset()
    }

    @After
    fun tearDown() {
        SDKComponent.reset()
    }

    @Test
    fun inlineMessageAvailabilityFlow_givenModuleNotInitialized_expectFalse() = runTest {
        assertFalse(inlineMessageAvailabilityFlow("promotion").first())
    }
}
