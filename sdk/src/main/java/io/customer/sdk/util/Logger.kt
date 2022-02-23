package io.customer.sdk.util

import android.util.Log

class Logger {

    private val tag = "[CIO]"

    fun info(message: String) {
        Log.i(tag, message)
    }

    fun debug(message: String) {
        Log.d(tag, message)
    }

    fun error(message: String) {
        Log.e(tag, message)
    }
}
