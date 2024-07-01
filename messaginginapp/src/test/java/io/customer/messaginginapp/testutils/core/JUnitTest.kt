package io.customer.messaginginapp.testutils.core

import android.util.Log
import io.customer.commontest.util.UnitTestLogger
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.mockk.Call
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
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

    companion object {
        private const val TAG = GIST_TAG

        @BeforeAll
        @JvmStatic
        fun setupStatic() {
            // Mock static methods of Log
            mockkStatic(Log::class)
            // Setup the behavior for Log.d
            val printLog: MockKAnswerScope<Int, Int>.(Call) -> Int = {
                val prefix = when (method.name) {
                    "d" -> "DEBUG"
                    "e" -> "ERROR"
                    "w" -> "WARN"
                    "i" -> "INFO"
                    "v" -> "VERBOSE"
                    else -> "UNKNOWN"
                }
                val tag = arg<String>(0)
                val message = arg<String>(1)
                println("[$prefix] $tag: $message")
                0
            }
            // Mock log methods as in-app messaging module currently uses Log class directly instead of Logger
            every { Log.d(TAG, any()) } answers printLog
            every { Log.e(TAG, any()) } answers printLog
            every { Log.i(TAG, any()) } answers printLog
            every { Log.v(TAG, any()) } answers printLog
            every { Log.w(TAG, any<String>()) } answers printLog
        }

        @AfterAll
        @JvmStatic
        fun teardownStatic() {
            // Unmock static methods of Log to avoid interference with other tests and any leaks
            unmockkStatic(Log::class)
        }
    }
}
