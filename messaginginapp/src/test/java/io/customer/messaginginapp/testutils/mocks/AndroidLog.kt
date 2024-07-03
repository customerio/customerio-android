package io.customer.messaginginapp.testutils.mocks

import android.util.Log
import io.customer.messaginginapp.gist.presentation.GIST_TAG
import io.customer.sdk.core.di.SDKComponent
import io.mockk.Call
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockkStatic

fun mockAndroidLog() {
    // Mock static methods of Log
    mockkStatic(Log::class)

    // Forward calls from Log to SDKComponent logger
    val forwardLog: MockKAnswerScope<Int, Int>.(Call) -> Int = {
        val logger = SDKComponent.logger
        val message = secondArg<String>()
        when (method.name) {
            "d" -> logger.debug(message)
            "e" -> logger.error(message)
            "i" -> logger.info(message)
            else -> {
                val prefix = method.name
                val tag = firstArg<String>()
                SDKComponent.logger.debug("[$prefix] $tag: $message")
                println("[$prefix] $tag: $message")
            }
        }
        0
    }

    // Mock log methods as in-app messaging module currently uses Log class directly instead of Logger
    every { Log.d(GIST_TAG, any()) } answers forwardLog
    every { Log.e(GIST_TAG, any()) } answers forwardLog
    every { Log.i(GIST_TAG, any()) } answers forwardLog
    every { Log.v(GIST_TAG, any()) } answers forwardLog
    every { Log.w(GIST_TAG, any<String>()) } answers forwardLog
}
