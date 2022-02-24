package io.customer.sdk.util

import android.util.Log

interface Logger {
    fun info(message: String)
    fun debug(message: String)
    fun error(message: String)
}

class LogcatLogger : Logger {

    private val tag = "[CIO]"

    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun debug(message: String) {
        Log.d(tag, message)
    }

    override fun error(message: String) {
        Log.e(tag, message)
    }
}
