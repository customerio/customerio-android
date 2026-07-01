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
 * For each pending entry it: cancels that entry's WorkManager unique work — so
 * the worker can't also deliver — then atomically
 * [claims][PendingDeliveryStore.claim] it and hands it to the caller's
 * [publish] block. Cancel happens before the claim on purpose: an `ENQUEUED`
 * or running worker flips to `CANCELLED` immediately, narrowing the window in
 * which both channels could race. Cancellation is best-effort and can lose
 * across process death, so the claim is what makes this flush deliver each
 * entry at most once. Whether the *system* is exactly- or at-least-once depends
 * on the paired worker: one that also claims before sending (push) is
 * exactly-once; one that sends-then-removes (geofence) is at-least-once and
 * relies on a stable id in the payload for downstream dedupe.
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
    fun flush(callbacks: Callbacks<T> = Callbacks(), publish: (T) -> Unit) {
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
                        // Atomically claim before publishing so this flush delivers
                        // each entry at most once (and a worker that also claims —
                        // push — can't deliver the same entry). Claiming per-entry
                        // here, rather than batching a remove at the end, also means a
                        // mid-loop CancellationException can't strand an already-
                        // published entry for re-publish next flush.
                        if (!store.claim(entry.key)) return@forEach
                        publish(entry)
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
