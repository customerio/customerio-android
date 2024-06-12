package io.customer.messaginginapp.support

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val DEFERRED_WAIT_MAX_DELAY = 1_000L

fun <T> Deferred<T>.awaitWithTimeoutBlocking(
    timeMillis: Long = DEFERRED_WAIT_MAX_DELAY
): T = runBlocking { withTimeout(timeMillis) { await() } }

fun <T> Collection<Deferred<T>>.awaitWithTimeoutBlocking(
    timeMillis: Long = DEFERRED_WAIT_MAX_DELAY
): List<T> = runBlocking { withTimeout(timeMillis) { awaitAll(*toTypedArray()) } }
