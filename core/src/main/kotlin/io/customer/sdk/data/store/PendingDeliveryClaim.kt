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

    /**
     * [send] reported success but the removing write did not land, so the entry is still queued.
     *
     * Distinct from [Delivered] because the work is done while the bookkeeping is not: a caller that
     * drains "the oldest entry" in a loop would otherwise read the same entry again and resend it
     * immediately, without bound. Hand this to a backoff-capable channel (a WorkManager retry) so the
     * duplicate — deduped backend-side on the stable payload id — costs one delayed attempt rather
     * than a tight loop.
     */
    object DeliveredNotRemoved : PendingDeliveryResult

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
 * invoked: a claim can fail because another channel already took the entry
 * ([AlreadyClaimed][PendingDeliveryResult.AlreadyClaimed]) or because its
 * removing write didn't land — the latter leaves the entry present and
 * undelivered, so it's reported [Retryable][PendingDeliveryResult.Retryable].
 * On failure the entry is [restored][PendingDeliveryStore.append] so a retry or
 * the flush can deliver it later; on success it stays removed.
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
    if (!claim(entry.key)) {
        // A false claim is either another channel taking the entry (now gone) or the removing write
        // failing (still present). Only the former was delivered elsewhere; a still-present entry was
        // claimed by no one, so retry rather than reporting success.
        return if (get(entry.key) == null) {
            PendingDeliveryResult.AlreadyClaimed
        } else {
            PendingDeliveryResult.Retryable(null)
        }
    }

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

/**
 * At-least-once counterpart to [claimSendRestore] for channels whose payload carries a stable
 * backend dedup id (geofence: `transitionId`).
 *
 * Unlike [claimSendRestore], the entry is **not** removed before sending — it stays until [send]
 * is confirmed, so a process death mid-send leaves the row for a retry or the foreground flush
 * instead of dropping it. The cost is a possible double-delivery (a concurrent channel, or a retry
 * after an ambiguous success), safe only because the backend dedupes on the stable id.
 *
 * The presence check is best-effort — not atomic with [send] — so it only trims a duplicate when
 * another channel already removed the entry. On failure the row is left in place (never removed)
 * so the next attempt can deliver it.
 *
 * A send that succeeds but whose removal doesn't persist reports
 * [PendingDeliveryResult.DeliveredNotRemoved], never [PendingDeliveryResult.Delivered]: the entry is
 * still queued, and a caller that assumed otherwise would resend it on its very next pass.
 */
@InternalCustomerIOApi
suspend fun <T : PendingDeliveryStore.PendingDeliveryEntry> PendingDeliveryStore<T>.sendRemoveOnSuccess(
    entry: T,
    isRetryable: (Throwable?) -> Boolean = { it is IOException },
    send: suspend () -> Result<Unit>
): PendingDeliveryResult {
    if (loadAll().none { it.key == entry.key }) return PendingDeliveryResult.AlreadyClaimed

    val result = send()
    return when {
        result.isSuccess -> if (remove(entry.key)) {
            PendingDeliveryResult.Delivered
        } else {
            PendingDeliveryResult.DeliveredNotRemoved
        }
        isRetryable(result.exceptionOrNull()) -> PendingDeliveryResult.Retryable(result.exceptionOrNull())
        else -> PendingDeliveryResult.Failed(result.exceptionOrNull())
    }
}
