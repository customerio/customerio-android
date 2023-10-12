package io.customer.sdk.api

import io.customer.sdk.util.Seconds
import io.customer.sdk.util.toSeconds

interface HttpRetryPolicy {
    val nextSleepTime: Seconds?
    fun reset()
}

internal class CustomerIOApiRetryPolicy : HttpRetryPolicy {

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

    private var retriesLeft: MutableList<Seconds> = mutableListOf()

    init {
        reset() // to populate fields and be ready for first request.
    }

    override val nextSleepTime: Seconds?
        get() = retriesLeft.removeFirstOrNull()

    // where all fields are populated in class. Single source of truth for initial values.
    override fun reset() {
        retriesLeft = retryPolicy.toMutableList()
    }
}
