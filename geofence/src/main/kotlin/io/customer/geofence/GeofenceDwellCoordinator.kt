package io.customer.geofence

import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegistrationIncarnation
import io.customer.sdk.communication.Event
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists one continuous visit and emits at most one dwell event from qualifying evidence.
 *
 * Native circle callbacks carry neither an occurrence time nor the registration that produced them,
 * so GMS can deliver an EXIT or DWELL from a replaced registration, or from an earlier visit, after
 * a newer visit began. Such a callback is attributed to a visit only when its triggering fix was
 * taken after that visit's registration and entry (see [belongsTo]). An unattributed callback never
 * ends or emits a dwell for a visit.
 */
internal class GeofenceDwellCoordinator(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor
) {
    /**
     * Starts or keeps the visit an ENTER describes. A repeated ENTER keeps the current visit unless
     * it provably follows a lost EXIT (see [departedSince]).
     *
     * @param beginsNewVisit containment did not already hold this fence, so the ENTER may replace a
     * visit that continuity lost. It is not proof the entry was observed: a fence registered, or a
     * polygon activated, around a device already inside reports ENTER without any crossing.
     * @param polygonOutsideObserved polygons only. The evaluator decisively saw the device outside
     * earlier in the same activation, so this ENTER is the crossing itself. Circles derive the same
     * proof from their registration instead (see [observedCircleEntry]).
     */
    suspend fun onEnter(
        geofenceId: String,
        enteredAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        beginsNewVisit: Boolean = false,
        entryFixElapsedMs: Long? = null,
        polygonOutsideObserved: Boolean = false
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        // A circle visit is only meaningful against a live registration: without one, no later
        // native EXIT or DWELL could ever be attributed to it.
        val incarnation = if (region.isPolygon) null else currentIncarnation(region)
        if (!region.tracksVisit() || (!region.isPolygon && incarnation == null)) {
            store.removeDwellVisit(geofenceId)
            return@withLock
        }
        val revision = region.transitionRevision()
        val generation = expectedUserStateGeneration
        val existing = currentVisit(region)
        var departed = false
        if (existing?.regionRevision == revision && existing.userStateGeneration == generation) {
            if (enteredAtSeconds < existing.enteredAtSeconds) return@withLock
            // Decided no later than a fix already attributed to this visit, so it describes this
            // stay or an earlier one, such as an ENTER delivered after the DWELL that recovered it.
            val lastInsideFix = existing.lastInsideFixElapsedMs
            if (entryFixElapsedMs != null && lastInsideFix != null && entryFixElapsedMs <= lastInsideFix) {
                return@withLock
            }
            departed = !beginsNewVisit && departedSince(existing, incarnation, entryFixElapsedMs)
            if (!beginsNewVisit && !departed) return@withLock
        }
        store.saveDwellVisit(
            GeofenceDwellVisit(
                geofenceId = geofenceId,
                visitId = UUID.randomUUID().toString(),
                enteredAtSeconds = enteredAtSeconds,
                regionRevision = revision,
                userStateGeneration = generation,
                // Otherwise the device arrived at some unknown earlier time (already inside at
                // registration or activation, or continuity was lost), and this timestamp is only
                // when we learned of it.
                entryWasObserved = (beginsNewVisit || departed) && if (region.isPolygon) {
                    polygonOutsideObserved
                } else {
                    observedCircleEntry(incarnation, entryFixElapsedMs)
                },
                registrationElapsedMs = incarnation?.registeredAtElapsedMs,
                entryFixElapsedMs = entryFixElapsedMs
            )
        )
    }

    /**
     * @param triggeringFixElapsedMs boot-relative time of the callback's triggering fix, or null
     * when GMS supplied none. It decides whether this DWELL came from the live registration.
     */
    suspend fun onNativeDwell(
        geofenceId: String,
        observedAtSeconds: Long,
        triggeringFixElapsedMs: Long?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        if (region.isPolygon || region.dwellThresholdSeconds <= 0) return@withLock
        val incarnation = currentIncarnation(region) ?: return@withLock
        // GMS can deliver callbacks out of order. A DWELL decided at or before a fix that already
        // reported this registration's EXIT describes a stay that has ended.
        val lastExitFix = incarnation.lastExitFixElapsedMs
        if (triggeringFixElapsedMs != null && lastExitFix != null && triggeringFixElapsedMs <= lastExitFix) {
            return@withLock
        }
        var visit = currentVisit(region)
        if (visit == null) {
            // GMS delivered dwell only after its loitering delay, so this callback is qualifying
            // proof even if the preceding ENTER callback was lost during process death. That holds
            // only for the live registration's delay: a DWELL from a replaced one proved some other
            // threshold, or geometry, and cannot recover a visit here.
            if (triggeringFixElapsedMs == null || triggeringFixElapsedMs < incarnation.registeredAtElapsedMs) {
                return@withLock
            }
            visit = GeofenceDwellVisit(
                geofenceId = geofenceId,
                visitId = UUID.randomUUID().toString(),
                enteredAtSeconds = (observedAtSeconds - region.dwellThresholdSeconds).coerceAtLeast(0),
                regionRevision = region.transitionRevision(),
                userStateGeneration = expectedUserStateGeneration,
                entryWasObserved = false,
                registrationElapsedMs = incarnation.registeredAtElapsedMs,
                lastInsideFixElapsedMs = triggeringFixElapsedMs
            )
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        // Only a DWELL attributed to this visit shows the device is still here. Elapsed time alone
        // does not: an unattributed EXIT deliberately leaves the visit in place, so the device may
        // have left long ago.
        if (!belongsTo(visit, triggeringFixElapsedMs)) return@withLock
        // belongsTo guarantees a fix. Recorded even once emitted: an outside proof older than this
        // fix was overruled by GMS still placing the device inside (see [departedSince]).
        val insideFix = triggeringFixElapsedMs ?: return@withLock
        if (insideFix > (visit.lastInsideFixElapsedMs ?: Long.MIN_VALUE)) {
            visit = visit.copy(lastInsideFixElapsedMs = insideFix)
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        emitIfDue(
            region = region,
            visit = visit,
            observedAtSeconds = observedAtSeconds,
            detectionSource = "native",
            nativeDwellProvesThreshold = true
        )
    }

    /** Called only with a fresh, decisive inside fix for a real polygon boundary. */
    suspend fun onInsideEvidence(
        geofenceId: String,
        observedAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        if (!region.isPolygon || region.dwellThresholdSeconds <= 0) return@withLock
        var visit = currentVisit(region)
        if (visit == null) {
            visit = GeofenceDwellVisit(
                geofenceId = geofenceId,
                visitId = UUID.randomUUID().toString(),
                enteredAtSeconds = observedAtSeconds,
                regionRevision = region.transitionRevision(),
                userStateGeneration = expectedUserStateGeneration,
                // The caller only passes fences containment already holds, so this rebuilds a
                // visit after continuity loss rather than observing its entry.
                entryWasObserved = false
            )
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        if (observedAtSeconds < visit.enteredAtSeconds) return@withLock
        // Once this visit emitted, later evidence cannot turn it back into a candidate. In
        // particular, a long evidence gap after emission must not reset `emitted` and create a
        // second dwell for the same continuous visit.
        if (visit.emitted) return@withLock
        emitIfDue(region, visit, observedAtSeconds, "location_evidence")
    }

    /**
     * Ends the visit a native EXIT provably belongs to.
     *
     * An EXIT that cannot be attributed leaves the visit alone. It may belong to a replaced
     * registration or an earlier visit, and a genuine departure is followed by a fresh ENTER, which
     * restarts the visit once containment has recorded the exit.
     */
    suspend fun onNativeExit(
        geofenceId: String,
        exitedAtSeconds: Long,
        triggeringFixElapsedMs: Long?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        val incarnation = currentIncarnation(region)
        if (
            incarnation != null &&
            triggeringFixElapsedMs != null &&
            triggeringFixElapsedMs >= incarnation.registeredAtElapsedMs
        ) {
            // Recorded even without a visit (a lost ENTER), so a DWELL this registration decided
            // before the exit cannot later recover the ended stay. See [onNativeDwell].
            store.recordNativeExitFix(geofenceId, incarnation.registeredAtElapsedMs, triggeringFixElapsedMs)
        }
        val visit = currentVisit(region) ?: return@withLock
        if (exitedAtSeconds < visit.enteredAtSeconds || !belongsTo(visit, triggeringFixElapsedMs)) {
            return@withLock
        }
        store.removeDwellVisit(geofenceId)
    }

    /**
     * Whether a repeated circle ENTER, arriving while containment still holds the fence, follows a
     * departure whose EXIT was lost. A second ENTER alone proves nothing: GMS can re-report one
     * without the device ever leaving. It needs this registration to have proved the device outside
     * after the visit's latest inside fix, and the ENTER's own fix to come after that proof. A visit
     * with no inside fix (an ENTER without one, or a recovered DWELL that predates this field) gives
     * no anchor to order the proof against, so it is kept.
     */
    private fun departedSince(
        visit: GeofenceDwellVisit,
        incarnation: GeofenceRegistrationIncarnation?,
        entryFixElapsedMs: Long?
    ): Boolean {
        val insideAt = maxOf(
            visit.entryFixElapsedMs ?: Long.MIN_VALUE,
            visit.lastInsideFixElapsedMs ?: Long.MIN_VALUE
        ).takeIf { it != Long.MIN_VALUE } ?: return false
        val outsideProvenAt = incarnation?.outsideProvenAtElapsedMs ?: return false
        return outsideProvenAt > insideAt && entryFixElapsedMs != null && entryFixElapsedMs > outsideProvenAt
    }

    /**
     * Whether a circle ENTER is the crossing itself: its triggering fix came after this registration
     * last proved the device outside. GMS's initial trigger for a device registered already inside
     * has no such proof before it, and neither does an ENTER without a triggering fix.
     */
    private fun observedCircleEntry(
        incarnation: GeofenceRegistrationIncarnation?,
        entryFixElapsedMs: Long?
    ): Boolean {
        val outsideProvenAt = incarnation?.outsideProvenAtElapsedMs ?: return false
        return entryFixElapsedMs != null && entryFixElapsedMs > outsideProvenAt
    }

    private fun currentIncarnation(region: GeofenceRegion): GeofenceRegistrationIncarnation? =
        store.getRegistrationIncarnation(region.id)?.takeIf { it.regionRevision == region.transitionRevision() }

    private fun currentVisit(region: GeofenceRegion): GeofenceDwellVisit? {
        val visit = store.getDwellVisit(region.id) ?: return null
        val registrationChanged = !region.isPolygon &&
            (
                visit.registrationElapsedMs == null ||
                    visit.registrationElapsedMs != currentIncarnation(region)?.registeredAtElapsedMs
                )
        if (
            visit.regionRevision != region.transitionRevision() ||
            visit.userStateGeneration != store.userStateGeneration() ||
            registrationChanged
        ) {
            store.removeDwellVisit(region.id)
            return null
        }
        return visit
    }

    /**
     * A triggering fix is a lower bound on when GMS decided the transition. One taken after the
     * visit's registration and after its entry fix therefore proves the callback is this visit's.
     * Without such a fix the callback may predate a re-registration or a newer entry.
     */
    private fun belongsTo(visit: GeofenceDwellVisit, triggeringFixElapsedMs: Long?): Boolean {
        val registeredAt = visit.registrationElapsedMs ?: return false
        if (triggeringFixElapsedMs == null || triggeringFixElapsedMs < registeredAt) return false
        return triggeringFixElapsedMs >= (visit.entryFixElapsedMs ?: registeredAt)
    }

    private suspend fun emitIfDue(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        observedAtSeconds: Long,
        detectionSource: String,
        nativeDwellProvesThreshold: Boolean = false
    ) {
        if (visit.emitted) return
        val observedDuration = (observedAtSeconds - visit.enteredAtSeconds).coerceAtLeast(0)
        // GMS emits DWELL only after its configured loitering delay. That proves the threshold even
        // when process death lost ENTER, but it does not reveal an observed entry time or duration.
        // Likewise, a delayed ENTER callback can make local elapsed time slightly short. Keep those
        // optional evidence fields absent instead of backdating them from the configured threshold.
        if (!nativeDwellProvesThreshold && observedDuration < region.dwellThresholdSeconds) return
        val observedEnteredAt = visit.enteredAtSeconds.takeIf { visit.entryWasObserved }
        val observedDwellDuration = observedDuration.takeIf {
            visit.entryWasObserved && it >= region.dwellThresholdSeconds
        }
        val result = transitionProcessor.process(
            geofenceId = region.id,
            transition = Event.GeofenceTransition.DWELL,
            timestampSeconds = observedAtSeconds,
            enforceConfiguredTransition = true,
            expectedRegionRevision = visit.regionRevision,
            expectedUserStateGeneration = visit.userStateGeneration,
            requireRegistered = true,
            visitContext = GeofenceTransitionEmitter.VisitContext.Dwell(
                visitId = visit.visitId,
                enteredAt = observedEnteredAt,
                thresholdSeconds = region.dwellThresholdSeconds,
                durationSeconds = observedDwellDuration,
                detectionSource = detectionSource
            )
        )
        val staged = store.getAllPendingTransitionEntries().any {
            it.geofenceId == region.id && it.transition == Event.GeofenceTransition.DWELL && it.visitId == visit.visitId
        }
        if (result == GeofenceTransitionEmitter.Result.PERSISTED || staged) {
            store.saveDwellVisit(visit.copy(emitted = true))
        }
    }

    private companion object {
        val mutex = Mutex()
    }
}

internal fun GeofenceRegion.tracksVisit(): Boolean = dwellThresholdSeconds > 0
