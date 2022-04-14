package io.customer.sdk.api

import io.customer.sdk.util.Seconds
import io.customer.sdk.util.toSeconds

interface HttpRetryPolicy {
    val nextSleepTime: Seconds?
}

class CustomerIOApiRetryPolicy : HttpRetryPolicy {

    companion object {
        internal val retryPolicy: List<Seconds> = listOf(
            0.1,
            0.2,
            0.4,
            0.8,
            1.6,
            3.2
        ).map { it.toSeconds() }
    }

    private var retriesLeft: MutableList<Seconds> = retryPolicy.toMutableList()

    override val nextSleepTime: Seconds?
        get() = retriesLeft.removeFirstOrNull()
}
