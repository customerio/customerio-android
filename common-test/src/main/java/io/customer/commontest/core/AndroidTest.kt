package io.customer.commontest.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.platform.app.InstrumentationRegistry
import io.customer.commontest.config.TestConfig
import org.junit.After
import org.junit.Before

open class AndroidTest : BaseTest() {
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
