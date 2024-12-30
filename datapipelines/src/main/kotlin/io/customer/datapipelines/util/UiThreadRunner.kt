package io.customer.datapipelines.util

import android.os.Handler
import android.os.Looper

internal class UiThreadRunner {

    private val mainThreadHandler = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        mainThreadHandler.post(block)
    }
}
