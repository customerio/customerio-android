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
 * Gated, per-geoset fan-out for one crossing: persist the batch, then schedule delivery.
 * Shared by the OS broadcast path ([GeofenceBroadcastReceiver]) and the synthesized initial-enter
 * path ([GeofenceRepository]) so both produce identical rows and pass the same gates.
 *
 * Two gates, in order: an ENTER already reported and not yet balanced by an EXIT is dropped, then
 * the time-based cooldown dedupes the rest. Shared by both paths, which is why they live here.
 */
internal class GeofenceTransitionEmitter(
    private val cooldownFilter: GeofenceCooldownFilter,
    private val pendingStore: PendingDeliveryStore<PendingGeofenceDelivery>,
    private val scheduler: GeofenceEventScheduler,
    private val regionStore: GeofenceRegionStore,
    private val logger: GeofenceLogger
) {
    internal enum class Result {
        PERSISTED,
        SUPPRESSED,
        PERSIST_FAILED
    }

    /**
     * @return false when the ENTER was already reported, the cooldown suppressed it, or the durable
     * outbox append failed. A failed append keeps its synchronous staging row for recovery.
     */
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
        expectedRegionRevision: Int?
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
        expectedRegionRevision = expectedRegionRevision
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
            first.userId?.let { userId ->
                cooldownFilter.record(userId, first.geofenceId, first.transition)
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
            // Physical containment was committed atomically with the stage. Recovery only
            // completes the durable delivery handoff; replaying old containment here could
            // overwrite a newer opposite transition.
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
        expectedRegionRevision: Int? = regionStore.getCachedRegion(geofenceId)?.transitionRevision()
    ): Result = emissionMutex.withLock {
        // Drain older attempts first. If storage is still unavailable, the new physical edge is
        // staged below but deliberately not appended, preserving delivery order for recovery.
        val olderAttemptsDrained = recoverPendingTransitionsLocked()
        // A business transition is a newly-confirmed physical edge, even when an older edge in the
        // same direction is still staged behind a failed outbox append (ENTER → EXIT → ENTER). Only
        // the direct synthetic path may reuse an existing stage for the same visit.
        val stagedEntries = if (retainAttempt) {
            emptyList()
        } else {
            regionStore.getPendingTransitionEntries(userId, geofenceId, transition)
        }
        val isRecovery = stagedEntries.isNotEmpty()
        // Reboot and app update both re-register with INITIAL_TRIGGER_ENTER, so the OS re-reports
        // ENTER for a fence the device never left. Ahead of the cooldown so the drop doesn't spend
        // the fence's slot, and only where an EXIT can clear the mark — an enter-only fence never
        // reports one, so the mark would swallow every later arrival.
        val isRedundantEnter = transition == Event.GeofenceTransition.ENTER &&
            monitorsExit &&
            regionStore.hasEmittedEnter(userId, geofenceId)
        if (!isRecovery && isRedundantEnter) {
            logger.logEnterDroppedAlreadyReported(geofenceId)
            return@withLock Result.SUPPRESSED
        }
        if (!isRecovery && !cooldownFilter.isAllowed(userId, geofenceId, transition)) {
            logger.logTransitionSuppressed(geofenceId, transition.name)
            return@withLock Result.SUPPRESSED
        }
        logger.logTransitionEmitting(geofenceId, transition.name)

        val entries = stagedEntries.ifEmpty {
            // One transitionId shared across the per-geoset fan-out.
            val transitionId = UUID.randomUUID().toString()
            val name = geofenceName?.takeIf { it.isNotEmpty() }
            // One event per geoset; no geosets → one null-geoset event. Distinct so a repeated
            // geoset doesn't duplicate.
            val geosets: List<String?> = geosetIds.distinct().takeIf { it.isNotEmpty() } ?: listOf(null)
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
                    marksEnterReported = transition == Event.GeofenceTransition.ENTER && monitorsExit
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
        // Only once the rows are durable, so an interrupted write can't suppress its own retry.
        cooldownFilter.record(userId, geofenceId, transition)
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
