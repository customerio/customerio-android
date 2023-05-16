package io.customer.android.sample.kotlin_compose.util

import android.util.Log

class Logger {
    companion object {
        private const val TAG = "KOTLIN_COMPOSE"
    }

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun v(message: String) {
        v(message, null)
    }

    fun v(message: String, throwable: Throwable?) {
        Log.v(TAG, message, throwable)
    }

    fun e(message: String) {
        e(message, null)
    }

    fun e(message: String, throwable: Throwable?) {
        Log.e(TAG, message, throwable)
    }
}
