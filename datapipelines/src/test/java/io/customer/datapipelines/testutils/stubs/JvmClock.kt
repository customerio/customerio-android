package io.customer.datapipelines.testutils.stubs

import io.customer.sdk.core.util.Clock
import java.util.concurrent.TimeUnit

/** [Clock] for JVM tests, where Android's `SystemClock` isn't available. */
class JvmClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun currentTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis())
    override fun elapsedRealtime(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}
