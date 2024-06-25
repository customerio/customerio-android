package io.customer.messaginginapp.testutils

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFERRED_WAIT_MAX_DELAY = 1_000L

fun <T> Deferred<T>.awaitWithTimeoutBlocking(
    timeMillis: Long = DEFERRED_WAIT_MAX_DELAY
): T = runBlocking {
    withTimeoutOrNull(timeMillis) {
        await()
    } ?: throw CancellationException("Failed to await deferred within $timeMillis milliseconds.")
}

fun <T> Collection<Deferred<T>>.awaitWithTimeoutBlocking(
    timeMillis: Long = DEFERRED_WAIT_MAX_DELAY
): List<T> = runBlocking {
    withTimeoutOrNull(timeMillis) {
        awaitAll(*toTypedArray())
    } ?: throw CancellationException("Failed to await deferred within $timeMillis milliseconds.")
}

fun CoroutineScope.runOnUIThreadBlocking(block: () -> Unit) {
    runBlocking {
        this@runOnUIThreadBlocking.launch(Dispatchers.Main) { block() }
    }
}
