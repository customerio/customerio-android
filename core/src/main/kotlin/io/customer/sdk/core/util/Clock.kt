package io.customer.sdk.core.util

import io.customer.base.internal.InternalCustomerIOApi
import java.util.concurrent.TimeUnit

/** Provides the current time. Injectable for testing. */
@InternalCustomerIOApi
interface Clock {
    fun currentTimeMillis(): Long
    fun currentTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis())
}

internal class SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
