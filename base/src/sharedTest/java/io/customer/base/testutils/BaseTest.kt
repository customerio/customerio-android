package io.customer.base.testutils

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.mockito.MockitoAnnotations

/**
 * Base class for test classes to subclass. Meant to provide convenience to test classes with properties and functions tests may use.
 */
abstract class BaseTest {

    open fun provideTestClass(): Any = this

    val siteId: String
        get() = "test-site-id"

    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    open fun setup() {
        // if this doesn't work, you can use the mockito test rule: MockitoJUnit.rule().
        MockitoAnnotations.initMocks(provideTestClass())
    }
}
