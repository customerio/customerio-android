package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Shared by the broadcast and synthesized initial-enter paths so both produce identical rows and
 * pass the same gates.
 */
internal class GeofenceTransitionEmitter(
    private val cooldownFilter: GeofenceCooldownFilter,
    private val pendingStore: PendingDeliveryStore<PendingGeofenceDelivery>,
    private val scheduler: GeofenceEventScheduler,
    private val regionStore: GeofenceRegionStore,
    private val logger: GeofenceLogger
) {
    internal sealed class VisitContext {
        abstract val visitId: String
        abstract val enteredAt: Long?
        abstract val detectionSource: String

        data class Dwell(
            override val visitId: String,
            override val enteredAt: Long?,
            val thresholdSeconds: Int,
            val durationSeconds: Long?,
            override val detectionSource: String
        ) : VisitContext()

        data class Exit(
            override val visitId: String,
            override val enteredAt: Long,
            val durationSeconds: Long,
            override val detectionSource: String
        ) : VisitContext()
    }
    internal enum class Result {
        PERSISTED,
        SUPPRESSED,
        PERSIST_FAILED
    }

    /** A failed outbox append keeps its staging row for recovery. */
    suspend fun emit(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>,
        monitorsExit: Boolean
    ): Boolean = emitWithResult(
        geofenceId = geofenceId,
        transition = transition,
        userId = userId,
        timestampSeconds = timestampSeconds,
        geofenceName = geofenceName,
        metadata = metadata,
        geosetIds = geosetIds,
        monitorsExit = monitorsExit
    ) == Result.PERSISTED

    suspend fun emitWithExpectedState(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>,
        monitorsExit: Boolean,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean = emitInternal(
        geofenceId,
        transition,
        userId,
        timestampSeconds,
        geofenceName,
        metadata,
        geosetIds,
        monitorsExit,
        retainAttempt = false,
        expectedUserStateGeneration = expectedUserStateGeneration,
        expectedRegionRevision = expectedRegionRevision
    ) == Result.PERSISTED

    suspend fun emitWithResult(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>,
        monitorsExit: Boolean
    ): Result = emitInternal(
        geofenceId,
        transition,
        userId,
        timestampSeconds,
        geofenceName,
        metadata,
        geosetIds,
        monitorsExit,
        retainAttempt = false
    )

    suspend fun emitWithRetainedAttempt(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>,
        monitorsExit: Boolean,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?,
        visitContext: VisitContext? = null
    ): Result = emitInternal(
        geofenceId,
        transition,
        userId,
        timestampSeconds,
        geofenceName,
        metadata,
        geosetIds,
        monitorsExit,
        retainAttempt = true,
        expectedUserStateGeneration = expectedUserStateGeneration,
        expectedRegionRevision = expectedRegionRevision,
        visitContext = visitContext
    )

    suspend fun recoverPendingTransitions(): Boolean = emissionMutex.withLock {
        recoverPendingTransitionsLocked()
    }

    private suspend fun recoverPendingTransitionsLocked(): Boolean {
        val attempts = regionStore.getAllPendingTransitionEntries()
            .groupBy(PendingGeofenceDelivery::transitionId)
            .values
        for (entries in attempts) {
            // Stop at the first unavailable append. Later transitions must not overtake it.
            if (!pendingStore.appendAll(entries)) return false
            val first = entries.first()
            if (first.transition != Event.GeofenceTransition.DWELL) {
                first.userId?.let { userId ->
                    cooldownFilter.record(userId, first.geofenceId, first.transition)
                }
            }
            entries.forEach { entry ->
                try {
                    scheduler.schedule(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.logSchedulerFailed(entry.geofenceId, entry.transition.name, e.message)
                }
            }
            // Containment was committed with the stage; replaying it here could overwrite a newer
            // opposite transition.
            if (!regionStore.completePendingTransition(first.transitionId)) return false
        }
        return true
    }

    private suspend fun emitInternal(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>,
        monitorsExit: Boolean,
        retainAttempt: Boolean,
        expectedUserStateGeneration: Long = regionStore.userStateGeneration(),
        expectedRegionRevision: Int? = regionStore.getCachedRegion(geofenceId)?.transitionRevision(),
        visitContext: VisitContext? = null
    ): Result = emissionMutex.withLock {
        // Drain older attempts first. If storage is still unavailable, the new edge is staged below
        // but not appended, preserving delivery order for recovery.
        val olderAttemptsDrained = recoverPendingTransitionsLocked()
        // A business transition is a new edge even if an older same-direction one is still staged
        // (ENTER, EXIT, ENTER), so only the synthetic path reuses a stage.
        val stagedEntries = if (retainAttempt) {
            emptyList()
        } else {
            regionStore.getPendingTransitionEntries(userId, geofenceId, transition)
        }
        val isRecovery = stagedEntries.isNotEmpty()
        // Reboot and app update re-report ENTER for a fence never left. Before the cooldown so the
        // drop spends no slot; needs monitorsExit, or no EXIT would ever clear the mark.
        val isRedundantEnter = transition == Event.GeofenceTransition.ENTER &&
            monitorsExit &&
            regionStore.hasEmittedEnter(userId, geofenceId)
        if (!isRecovery && isRedundantEnter) {
            logger.logEnterDroppedAlreadyReported(geofenceId)
            return@withLock Result.SUPPRESSED
        }
        val suppressedFor = if (isRecovery || transition == Event.GeofenceTransition.DWELL) {
            null
        } else {
            cooldownFilter.suppressedForSeconds(userId, geofenceId, transition)
        }
        if (suppressedFor != null) {
            logger.logTransitionSuppressed(geofenceId, transition.name, suppressedFor)
            return@withLock Result.SUPPRESSED
        }
        val entries = stagedEntries.ifEmpty {
            // One transitionId shared across the per-geoset fan-out.
            // A dwell retry belongs to the same continuous visit. Reusing its ID keeps a crash
            // between outbox persistence and the emitted-state write idempotent downstream.
            val transitionId = (visitContext as? VisitContext.Dwell)?.visitId ?: UUID.randomUUID().toString()
            val name = geofenceName?.takeIf { it.isNotEmpty() }
            // Blank and duplicate geoset ids are dropped, matching iOS.
            val geosets: List<String?> = geosetIds.filter { it.isNotEmpty() }.distinct()
                .takeIf { it.isNotEmpty() } ?: listOf(null)
            geosets.map { geosetId ->
                PendingGeofenceDelivery(
                    geofenceId = geofenceId,
                    transition = transition,
                    timestamp = timestampSeconds,
                    userId = userId,
                    transitionId = transitionId,
                    geofenceName = name,
                    geosetId = geosetId,
                    metadata = metadata,
                    stateGeneration = expectedUserStateGeneration,
                    regionRevision = expectedRegionRevision,
                    marksEnterReported = transition == Event.GeofenceTransition.ENTER && monitorsExit,
                    visitId = visitContext?.visitId,
                    enteredAt = visitContext?.enteredAt,
                    dwellThresholdSeconds = (visitContext as? VisitContext.Dwell)?.thresholdSeconds,
                    dwellDurationSeconds = (visitContext as? VisitContext.Dwell)?.durationSeconds,
                    visitDurationSeconds = (visitContext as? VisitContext.Exit)?.durationSeconds,
                    detectionSource = visitContext?.detectionSource
                )
            }.also { created ->
                if (!regionStore.savePendingTransitionEntries(created, expectedUserStateGeneration)) {
                    logger.logPersistFailed(geofenceId, transition.name)
                    return@withLock Result.PERSIST_FAILED
                }
            }
        }

        if (!olderAttemptsDrained) return@withLock Result.PERSIST_FAILED

        // Persist atomically before any send so an app kill mid-batch can't lose part of the fan-out.
        // Staging survives a write failure or process death, and stable keys make recovery idempotent.
        if (!pendingStore.appendAll(entries)) {
            logger.logPersistFailed(geofenceId, transition.name)
            return@withLock Result.PERSIST_FAILED
        }
        // After the write, so a failed write can't record a cooldown that suppresses its own retry.
        logger.logTransitionAccepted(geofenceId, transition.name, entries.size)
        if (transition != Event.GeofenceTransition.DWELL) {
            cooldownFilter.record(userId, geofenceId, transition)
        }
        entries.forEach { entry ->
            // Isolate the scheduler so one failure can't abandon the rest of the batch.
            try {
                scheduler.schedule(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logSchedulerFailed(geofenceId, transition.name, e.message)
            }
        }
        if (!retainAttempt) {
            regionStore.completePendingTransition(entries.first().transitionId)
        }
        Result.PERSISTED
    }

    private companion object {
        val emissionMutex = Mutex()
    }
}
