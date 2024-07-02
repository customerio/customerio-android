package io.customer.commontest.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.commontest.config.TestConfig
import org.junit.After
import org.junit.Before

/**
 * Android test base class for all Android tests in the project.
 * This class is responsible for basic setup and teardown of Android test environment.
 * The class should only contain the common setup and teardown logic for all Android tests.
 * Any additional setup or teardown logic should be implemented in respective child classes.
 * This class is responsible for mocking Android application and context objects.
 *
 * Tests extending this class should make sure to import JUnit4 imports for test annotations.
 * e.g. import org.junit.Test
 */
abstract class AndroidTest : BaseTest() {
    protected val application: Application = ApplicationProvider.getApplicationContext()
    protected val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setupTestEnvironment() {
        setup()
    }

    @After
    fun teardownTestEnvironment() {
        teardown()
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        Intents.init()
    }

    override fun teardown() {
        Intents.release()

        super.teardown()
    }
}
