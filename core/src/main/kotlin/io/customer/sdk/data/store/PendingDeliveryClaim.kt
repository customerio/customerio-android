package io.customer.sdk.data.store

import io.customer.base.internal.InternalCustomerIOApi
import java.io.IOException

/**
 * Outcome of a [claimSendRestore] attempt. Delivery channels (the push and
 * geofence WorkManager workers) map this onto their own result type
 * (e.g. a `ListenableWorker.Result`).
 */
@InternalCustomerIOApi
sealed interface PendingDeliveryResult {
    /** The entry was claimed and [send] reported success; the entry is gone. */
    object Delivered : PendingDeliveryResult

    /**
     * The entry was already gone when we tried to claim it — another channel
     * (e.g. the foreground flush) delivered it. Treat as success; do not send.
     */
    object AlreadyClaimed : PendingDeliveryResult

    /** [send] failed transiently; the entry was restored so a retry can deliver it later. */
    data class Retryable(val cause: Throwable?) : PendingDeliveryResult

    /** [send] failed permanently; the entry was restored so the foreground flush can still try. */
    data class Failed(val cause: Throwable?) : PendingDeliveryResult
}

/**
 * Shared "exactly-once" decision for a delivery channel that competes with
 * other channels over the same [entry] (typically a WorkManager worker racing
 * the foreground flush).
 *
 * Atomically [claims][PendingDeliveryStore.claim] the entry before sending —
 * a read-only check is not enough, since a slow [send] would let both channels
 * act on the same still-present entry. If the claim is lost, [send] is never
 * invoked. On failure the entry is [restored][PendingDeliveryStore.append] so
 * a retry or the flush can deliver it later; on success it stays removed.
 *
 * Both the push and geofence workers share this so the claim/send/restore
 * logic lives in one place — only the per-channel [send] call differs.
 */
@InternalCustomerIOApi
suspend fun <T : PendingDeliveryStore.PendingDeliveryEntry> PendingDeliveryStore<T>.claimSendRestore(
    entry: T,
    isRetryable: (Throwable?) -> Boolean = { it is IOException },
    send: suspend () -> Result<Unit>
): PendingDeliveryResult {
    if (!claim(entry.key)) return PendingDeliveryResult.AlreadyClaimed

    val result = send()
    return when {
        result.isSuccess -> PendingDeliveryResult.Delivered
        isRetryable(result.exceptionOrNull()) -> {
            append(entry)
            PendingDeliveryResult.Retryable(result.exceptionOrNull())
        }
        else -> {
            append(entry)
            PendingDeliveryResult.Failed(result.exceptionOrNull())
        }
    }
}
