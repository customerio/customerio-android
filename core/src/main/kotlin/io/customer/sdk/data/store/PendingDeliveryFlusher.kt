package io.customer.sdk.data.store

import androidx.work.await
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.CustomerIOWorkManagerProvider
import io.customer.sdk.core.util.DispatchersProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drains a [PendingDeliveryStore] through an opportunistic in-process channel
 * (typically an app-foreground handoff), coordinating with the durable
 * WorkManager channel that also consumes the same store.
 *
 * For each pending entry it cancels that entry's WorkManager unique work — so the worker can't
 * also deliver — then spends it against the store per the caller's [DeliveryGuarantee] and hands
 * it to [publish]. Cancel is best-effort (can lose across process death), so the store op is what
 * actually bounds duplicates; match the guarantee to the paired worker (push claims →
 * [DeliveryGuarantee.AT_MOST_ONCE]; geofence sends-then-removes → [DeliveryGuarantee.AT_LEAST_ONCE]).
 *
 * Entries are processed in isolation: one failed cancel/publish does not abort
 * the batch, and an unclaimed or failed entry survives for the next flush. The
 * worker's unique-work name must equal [PendingDeliveryStore.PendingDeliveryEntry.key].
 *
 * Both push (`handoffPendingPushDeliveryToAnalyticsPipeline`) and geofence
 * share this so the drain logic lives in one place — only the [publish]
 * transport and the [Callbacks] logging differ per feature.
 */
@InternalCustomerIOApi
class PendingDeliveryFlusher<T : PendingDeliveryStore.PendingDeliveryEntry>(
    private val store: PendingDeliveryStore<T>,
    private val workManagerProvider: CustomerIOWorkManagerProvider,
    private val dispatchersProvider: DispatchersProvider
) {

    /** How the foreground flush spends a pending entry against a possible mid-publish crash. */
    enum class DeliveryGuarantee {
        /**
         * Claim (remove) before publishing. If the process dies after the claim the entry is gone,
         * so it delivers at most once. Pair with a worker that also claims — together exactly-once.
         */
        AT_MOST_ONCE,

        /**
         * Publish, then remove only after it returns. A crash between the two leaves the entry for
         * the next flush to re-publish, so it delivers at least once; requires a stable payload id
         * for downstream dedupe. Pair with a send-then-remove worker.
         */
        AT_LEAST_ONCE
    }

    /**
     * Per-feature observation hooks. All default to no-ops so callers only
     * override the ones they log. Invoked on the background coroutine.
     */
    open class Callbacks<T> {
        /** Number of entries snapshotted at the start of the flush (may be 0). */
        open fun onSnapshot(count: Int) {}

        /** The entry's WorkManager unique work was cancelled. */
        open fun onWorkCancelled(entry: T) {}

        /** The entry was claimed and handed to `publish`. */
        open fun onPublished(entry: T) {}

        /** Processing this entry threw; it stays in the store for the next flush. */
        open fun onEntryFailed(entry: T, cause: Throwable) {}

        /** The flush finished; [count] entries were published this run. */
        open fun onComplete(count: Int) {}
    }

    /**
     * Launch a background drain of the store. Returns immediately; the work runs
     * on [DispatchersProvider.background]. Safe to call on every foreground
     * transition — an empty store is a cheap no-op.
     */
    fun flush(
        callbacks: Callbacks<T> = Callbacks(),
        guarantee: DeliveryGuarantee = DeliveryGuarantee.AT_MOST_ONCE,
        publish: (T) -> Unit
    ) {
        CoroutineScope(dispatchersProvider.background).launch {
            runCatching {
                val pending = store.loadAll()
                callbacks.onSnapshot(pending.size)
                if (pending.isEmpty()) return@runCatching

                val workManager = workManagerProvider.getWorkManager()
                var publishedCount = 0
                pending.forEach { entry ->
                    try {
                        if (workManager != null) {
                            workManager.cancelUniqueWork(entry.key).await()
                            callbacks.onWorkCancelled(entry)
                        }
                        // Order claim vs. publish per the caller's crash-safety guarantee (see [DeliveryGuarantee]).
                        when (guarantee) {
                            DeliveryGuarantee.AT_MOST_ONCE -> {
                                if (!store.claim(entry.key)) return@forEach
                                publish(entry)
                            }
                            DeliveryGuarantee.AT_LEAST_ONCE -> {
                                publish(entry)
                                store.remove(entry.key)
                            }
                        }
                        callbacks.onPublished(entry)
                        publishedCount++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ex: Exception) {
                        callbacks.onEntryFailed(entry, ex)
                    }
                }
                callbacks.onComplete(publishedCount)
            }
        }
    }
}
