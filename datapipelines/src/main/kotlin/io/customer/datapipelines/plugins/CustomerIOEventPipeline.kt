package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Storage
import com.segment.analytics.kotlin.core.parseFilePaths
import com.segment.analytics.kotlin.core.platform.EventPipeline
import com.segment.analytics.kotlin.core.platform.plugins.logger.log
import com.segment.analytics.kotlin.core.platform.policies.FlushPolicy
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.util.Clock
import kotlin.math.min
import kotlin.random.Random

/**
 * Segment's [EventPipeline], with its view of queued batch files gated by [UploadGateStorage]
 * so failing uploads back off instead of retrying every queued file on every flush.
 */
internal class CustomerIOEventPipeline(
    analytics: Analytics,
    logTag: String,
    apiKey: String,
    flushPolicies: List<FlushPolicy>,
    apiHost: String
) : EventPipeline(analytics, logTag, apiKey, flushPolicies, apiHost) {
    override val storage: Storage by lazy {
        UploadGateStorage(analytics.storage, SDKComponent.clock, onBackoff = { delayMillis ->
            analytics.log("$logTag no batch uploaded, next attempt ${delayMillis / 1000} s after that one")
        })
    }
}

/**
 * Forwards everything to [storage] except the list of queued batch files, which the pipeline
 * reads once per flush and then tries to upload in full. If none of the files a flush was given
 * left the queue, every upload failed, so:
 * - flushes see no files until the backoff ends, then one queued file;
 * - once a flush gets a file out, flushes see every file again.
 * A flush that starts failing still tries every file once; the backoff starts after it.
 */
internal class UploadGateStorage(
    private val storage: Storage,
    private val clock: Clock,
    private val random: Random = Random.Default,
    private val onBackoff: (delayMillis: Long) -> Unit = {}
) : Storage by storage {
    // Only read and written by the pipeline's upload loop, one flush at a time
    private var handedOut: Set<String> = emptySet()
    private var handedOutAtMillis = 0L
    private var consecutiveFailures = 0
    private var nextAttemptAtMillis = 0L

    override fun read(key: Storage.Constants): String? {
        val value = storage.read(key)
        if (key != Storage.Constants.Events) return value

        val files = parseFilePaths(value)
        if (handedOut.isNotEmpty()) {
            if (files.containsAll(handedOut)) onFailure() else consecutiveFailures = 0
        }

        val now = clock.elapsedRealtime()
        val allowed = when {
            consecutiveFailures == 0 -> files
            now < nextAttemptAtMillis -> emptyList()
            // Any queued file, so one batch the server keeps failing can't hold back the rest
            files.isNotEmpty() -> listOf(files.random(random))
            else -> emptyList()
        }
        handedOut = allowed.toSet()
        handedOutAtMillis = now
        return allowed.joinToString(",")
    }

    private fun onFailure() {
        consecutiveFailures++
        val delay = backoffMillis(consecutiveFailures)
        // Counted from the failed attempt, not from this later flush
        nextAttemptAtMillis = handedOutAtMillis + delay
        onBackoff(delay)
    }

    private fun backoffMillis(failures: Int): Long {
        val exponential = BASE_BACKOFF_MILLIS * (1L shl min(failures - 1, 16))
        val capped = min(exponential, MAX_BACKOFF_MILLIS)
        // Up to 10% jitter so devices don't retry in step after an outage
        return capped + (random.nextDouble() * capped * 0.1).toLong()
    }

    companion object {
        private const val BASE_BACKOFF_MILLIS = 30_000L
        private const val MAX_BACKOFF_MILLIS = 5 * 60 * 1000L
    }
}
