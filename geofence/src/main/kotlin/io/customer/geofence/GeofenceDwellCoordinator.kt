package io.customer.geofence

import io.customer.geofence.polygon.PolygonBootSessionProvider
import io.customer.geofence.store.GeofenceDwellReservation
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.GeofenceRegistrationIncarnation
import io.customer.sdk.communication.Event
import io.customer.sdk.core.util.Clock
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
 *
 * Visits are ordered and measured on the boot clock, never wall time (see [GeofenceVisitTiming]).
 */
internal class GeofenceDwellCoordinator(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    private val bootSessionProvider: PolygonBootSessionProvider
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
     * @param enteredAtElapsedMs elapsedRealtime read with [enteredAtSeconds]; null means now.
     */
    suspend fun onEnter(
        geofenceId: String,
        enteredAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        beginsNewVisit: Boolean = false,
        entryFixElapsedMs: Long? = null,
        polygonOutsideObserved: Boolean = false,
        enteredAtElapsedMs: Long? = null
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
        // Decided no later than an EXIT this registration already reported, so the stay it describes
        // has ended. The pipeline commits an ENTER before this write, so its EXIT can land first.
        val lastExitFix = incarnation?.lastExitFixElapsedMs
        if (entryFixElapsedMs != null && lastExitFix != null && entryFixElapsedMs <= lastExitFix) {
            return@withLock
        }
        val bootSessionId = bootSessionProvider.currentSessionId()
        val startedAtElapsedMs = enteredAtElapsedMs ?: clock.elapsedRealtime()
        val revision = region.transitionRevision()
        val generation = expectedUserStateGeneration
        val existing = currentVisit(region, bootSessionId)
        var departed = false
        if (existing?.regionRevision == revision && existing.userStateGeneration == generation) {
            if (beginsNewVisit && recoversObservedEntry(existing, incarnation, entryFixElapsedMs)) {
                // This ENTER is the observed crossing, but a repeat committed after it wrote its
                // visit first. Keep that visit and date it from this entry instead.
                store.saveDwellVisit(
                    existing.copy(
                        enteredAtSeconds = enteredAtSeconds,
                        enteredAtElapsedMs = startedAtElapsedMs,
                        entryFixElapsedMs = entryFixElapsedMs,
                        entryWasObserved = true,
                        lastInsideFixElapsedMs = latestInsideFix(existing)
                    )
                )
                return@withLock
            }
            // On the boot clock, so a wall-clock step cannot hide a newer ENTER. A visit without that
            // stamp predates it and is ordered by wall time.
            val startedBeforeExisting = existing.enteredAtElapsedMs?.let { startedAtElapsedMs < it }
                ?: (enteredAtSeconds < existing.enteredAtSeconds)
            if (startedBeforeExisting) return@withLock
            // Decided no later than a fix already attributed to this visit, its entry included, so it
            // describes this stay or an earlier one: a duplicate, or an ENTER delivered after the
            // DWELL that recovered the visit.
            val insideFix = latestInsideFix(existing)
            if (entryFixElapsedMs != null && insideFix != null && entryFixElapsedMs <= insideFix) {
                // A distinct ENTER may have reached this write after its own DWELL. It still
                // makes the original entry ambiguous, while an exact duplicate says nothing new.
                if (!region.isPolygon && existing.entryWasObserved &&
                    entryFixElapsedMs != existing.entryFixElapsedMs && belongsTo(existing, entryFixElapsedMs)
                ) {
                    store.saveDwellVisit(existing.copy(entryWasObserved = false))
                }
                return@withLock
            }
            departed = !beginsNewVisit && departedSince(existing, incarnation, entryFixElapsedMs)
            if (!beginsNewVisit && !departed) {
                // A repeated ENTER of this visit is GMS placing the device inside at a later fix, which
                // an EXIT decided before it must not override (see [onNativeExit]). Its fix is newer
                // than any this visit holds, and GMS may be re-reporting or the device may have come
                // back after an EXIT the process never handled. The visit, its queued DWELL and its
                // emitted mark stand, but its entry no longer dates the current stay.
                if (entryFixElapsedMs == null && !region.isPolygon && existing.entryWasObserved) {
                    // Without a fix, this callback cannot identify itself as the original ENTER.
                    store.saveDwellVisit(existing.copy(entryWasObserved = false))
                } else if (entryFixElapsedMs != null && belongsTo(existing, entryFixElapsedMs)) {
                    store.saveDwellVisit(
                        existing.copy(lastInsideFixElapsedMs = entryFixElapsedMs, entryWasObserved = false)
                    )
                }
                return@withLock
            }
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
                entryFixElapsedMs = entryFixElapsedMs,
                bootSessionId = bootSessionId,
                enteredAtElapsedMs = startedAtElapsedMs
            )
        )
    }

    /**
     * @param triggeringFixElapsedMs boot-relative time of the callback's triggering fix, or null
     * when GMS supplied none. It decides whether this DWELL came from the live registration.
     * @param observedAtElapsedMs elapsedRealtime read with [observedAtSeconds]; null means now.
     */
    suspend fun onNativeDwell(
        geofenceId: String,
        observedAtSeconds: Long,
        triggeringFixElapsedMs: Long?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        observedAtElapsedMs: Long? = null
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
        val bootSessionId = bootSessionProvider.currentSessionId()
        val atElapsedMs = observedAtElapsedMs ?: clock.elapsedRealtime()
        var visit = currentVisit(region, bootSessionId)
        // This registration saw the device decisively outside after everything that placed it inside
        // the stored visit, so that stay has ended even though GMS reported no EXIT.
        val outsideAt = incarnation.outsideProvenAtElapsedMs
        val visitEnded = visit != null && outsideAt != null && outsideAt > insideAnchor(visit)
        if (visit == null || visitEnded) {
            val fix = triggeringFixElapsedMs ?: return@withLock
            // Decided before the departure, so it describes the stay that ended.
            if (outsideAt != null && fix <= outsideAt) return@withLock
            if (!gmsReenteredSince(incarnation, outsideAt, fix)) {
                // Inside again, but GMS may still be counting its loitering from the old ENTER,
                // so this cannot qualify the new stay. A later EXIT must not date it from that
                // ENTER either.
                if (visit != null && visit.entryWasObserved) store.saveDwellVisit(visit.copy(entryWasObserved = false))
                return@withLock
            }
            // GMS delivered dwell only after its loitering delay, so this callback is qualifying
            // proof even if the preceding ENTER callback was lost during process death. That holds
            // only for the live registration's delay: a DWELL from a replaced one proved some other
            // threshold, or geometry, and cannot recover a visit here.
            if (fix < incarnation.registeredAtElapsedMs) return@withLock
            visit = GeofenceDwellVisit(
                geofenceId = geofenceId,
                visitId = UUID.randomUUID().toString(),
                // The arrival time is unknown, so the visit starts at this proof instead of being
                // backdated by the threshold.
                enteredAtSeconds = observedAtSeconds,
                regionRevision = region.transitionRevision(),
                userStateGeneration = expectedUserStateGeneration,
                entryWasObserved = false,
                registrationElapsedMs = incarnation.registeredAtElapsedMs,
                lastInsideFixElapsedMs = triggeringFixElapsedMs,
                bootSessionId = bootSessionId,
                enteredAtElapsedMs = atElapsedMs
            )
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        // Only a DWELL attributed to this visit shows the device is still here. Elapsed time alone
        // does not: an unattributed EXIT deliberately leaves the visit in place, so the device may
        // have left long ago.
        if (!belongsTo(visit, triggeringFixElapsedMs)) return@withLock
        // belongsTo guarantees a fix. Recorded even once emitted: the device is inside at this fix,
        // so only outside proof after it can end the visit (see [departedSince] and above).
        val insideFix = triggeringFixElapsedMs ?: return@withLock
        if (insideFix > (visit.lastInsideFixElapsedMs ?: Long.MIN_VALUE)) {
            visit = visit.copy(lastInsideFixElapsedMs = insideFix)
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        emitIfDue(
            region = region,
            visit = visit,
            observedAtSeconds = observedAtSeconds,
            observedAtElapsedMs = atElapsedMs,
            bootSessionId = bootSessionId,
            detectionSource = "native",
            nativeDwellProvesThreshold = true
        )
    }

    /**
     * Called only with a fresh, decisive inside fix for a real polygon boundary.
     * @param observedAtElapsedMs the fix's elapsedRealtime; null means now.
     */
    suspend fun onInsideEvidence(
        geofenceId: String,
        observedAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        observedAtElapsedMs: Long? = null
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        if (!region.isPolygon || region.dwellThresholdSeconds <= 0) return@withLock
        val bootSessionId = bootSessionProvider.currentSessionId()
        val atElapsedMs = observedAtElapsedMs ?: clock.elapsedRealtime()
        var visit = currentVisit(region, bootSessionId)
        if (visit != null && !visit.emitted && visit.enteredAtElapsedMs == null) {
            // Predates timing provenance, so how long it has lasted is unknown. Restart it as a
            // candidate from this evidence rather than trust its wall-clock entry.
            visit = visit.copy(
                enteredAtSeconds = observedAtSeconds,
                entryWasObserved = false,
                bootSessionId = bootSessionId,
                enteredAtElapsedMs = atElapsedMs
            )
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        if (visit == null) {
            visit = GeofenceDwellVisit(
                geofenceId = geofenceId,
                visitId = UUID.randomUUID().toString(),
                enteredAtSeconds = observedAtSeconds,
                regionRevision = region.transitionRevision(),
                userStateGeneration = expectedUserStateGeneration,
                // The caller only passes fences containment already holds, so this rebuilds a
                // visit after continuity loss rather than observing its entry.
                entryWasObserved = false,
                bootSessionId = bootSessionId,
                enteredAtElapsedMs = atElapsedMs
            )
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        // A fix older than the visit's start describes an earlier stay.
        if (atElapsedMs < (visit.enteredAtElapsedMs ?: atElapsedMs)) return@withLock
        // Once this visit emitted, later evidence cannot turn it back into a candidate. In
        // particular, a long evidence gap after emission must not reset `emitted` and create a
        // second dwell for the same continuous visit.
        if (visit.emitted) return@withLock
        emitIfDue(region, visit, observedAtSeconds, atElapsedMs, bootSessionId, "location_evidence")
    }

    /**
     * Whether a polygon's current visit had lasted its dwell threshold at [atElapsedMs] and still
     * waits for evidence to emit its DWELL, so a more precise fix could qualify it. Reads only: a
     * stale visit is left to the paths that act on it. A reserved visit already holds its evidence,
     * and one stamped in another boot or before boot-clock stamps has no measurable duration.
     *
     * @param atElapsedMs boot-clock time of the fix that kept the polygon inside without showing it.
     */
    suspend fun awaitsDwellProof(
        geofenceId: String,
        atElapsedMs: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ): Boolean = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock false
        val region = store.getCachedRegion(geofenceId) ?: return@withLock false
        if (!region.isPolygon || region.dwellThresholdSeconds <= 0) return@withLock false
        val visit = store.getDwellVisit(geofenceId) ?: return@withLock false
        if (visit.regionRevision != region.transitionRevision() ||
            visit.userStateGeneration != expectedUserStateGeneration ||
            visit.emitted ||
            visit.dwellReservation != null
        ) {
            return@withLock false
        }
        val elapsedMs = GeofenceVisitTiming.elapsedMs(visit, bootSessionProvider.currentSessionId(), atElapsedMs)
            ?: return@withLock false
        elapsedMs >= region.dwellThresholdSeconds * MILLIS_PER_SECOND
    }

    /**
     * Ends the visit a committed polygon EXIT ended, ordered by the EXIT's fix rather than wall time:
     * after a backward clock step the processor's wall-time cleanup would keep it, and the next inside
     * fix could then resume it across the time spent outside.
     */
    suspend fun onPolygonExit(
        geofenceId: String,
        exitFixElapsedMs: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId)?.takeIf { it.isPolygon } ?: return@withLock
        val visit = currentVisit(region, bootSessionProvider.currentSessionId()) ?: return@withLock
        // A visit begun after this fix is a newer stay.
        val startedAt = visit.enteredAtElapsedMs ?: return@withLock
        if (exitFixElapsedMs >= startedAt) store.removeDwellVisit(geofenceId)
    }

    /**
     * Ends the visit a native EXIT provably belongs to.
     *
     * An EXIT that cannot be attributed leaves the visit alone. It may belong to a replaced
     * registration or an earlier visit, and a genuine departure is followed by a fresh ENTER, which
     * restarts the visit once containment has recorded the exit.
     *
     * @param exitedAtElapsedMs elapsedRealtime read with [exitedAtSeconds]; null means now.
     * @return false when the EXIT is older than evidence already placing the device inside.
     */
    suspend fun onNativeExit(
        geofenceId: String,
        exitedAtSeconds: Long,
        triggeringFixElapsedMs: Long?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        exitedAtElapsedMs: Long? = null
    ): Boolean = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock true
        val region = store.getCachedRegion(geofenceId) ?: return@withLock true
        val incarnation = currentIncarnation(region)
        val visit = currentVisit(region, bootSessionProvider.currentSessionId())
        // GMS decided this EXIT no later than a fix that placed the device inside, this visit's or a
        // newer ENTER's, so it describes a departure already overruled. It must not end the visit or
        // move containment.
        val latestInsideFix = listOfNotNull(visit?.let(::latestInsideFix), incarnation?.lastEnterFixElapsedMs).maxOrNull()
        if (triggeringFixElapsedMs != null && latestInsideFix != null && triggeringFixElapsedMs <= latestInsideFix) {
            if (incarnation != null && triggeringFixElapsedMs >= incarnation.registeredAtElapsedMs) {
                // Still a fix this registration judged outside, so no ENTER decided before it can
                // later count as the start of the current stay.
                store.raiseOutsideProof(setOf(geofenceId), triggeringFixElapsedMs, bootSessionProvider.currentSessionId())
            }
            // Decided at or after the visit's entry (a tie is ambiguous), so the device left
            // after that entry and came back unobserved. The visit, its queued DWELL and its
            // inside evidence stand; only the entry no longer dates the current stay.
            if (visit != null && visit.entryWasObserved && belongsTo(visit, triggeringFixElapsedMs)) {
                store.saveDwellVisit(visit.copy(entryWasObserved = false))
            }
            return@withLock false
        }
        if (
            incarnation != null &&
            triggeringFixElapsedMs != null &&
            triggeringFixElapsedMs >= incarnation.registeredAtElapsedMs
        ) {
            // Recorded even without a visit (a lost ENTER), so a DWELL this registration decided
            // before the exit cannot later recover the ended stay. See [onNativeDwell].
            store.recordNativeExitFix(geofenceId, incarnation.registeredAtElapsedMs, triggeringFixElapsedMs)
        }
        visit ?: return@withLock true
        // Received before the visit began, so it describes an earlier stay. Compared on the boot
        // clock: a wall clock stepped back since the ENTER must not keep the visit this EXIT ended.
        val receivedAtElapsedMs = exitedAtElapsedMs ?: clock.elapsedRealtime()
        val receivedBeforeVisit = visit.enteredAtElapsedMs?.let { receivedAtElapsedMs < it }
            ?: (exitedAtSeconds < visit.enteredAtSeconds)
        if (receivedBeforeVisit) return@withLock true
        if (!belongsTo(visit, triggeringFixElapsedMs)) {
            // The visit stays: a stale callback must not end it, and its emitted mark keeps a
            // redelivered DWELL from repeating. But this EXIT is still delivered and moves
            // containment outside, so no later DWELL may date the stay from an entry before it.
            if (visit.entryWasObserved && geofenceId in store.getEnteredIds()) {
                store.saveDwellVisit(visit.copy(entryWasObserved = false))
            }
            return@withLock true
        }
        store.removeDwellVisit(geofenceId)
        true
    }

    /**
     * Orders a native ENTER or EXIT by its triggering fix against this registration's opposite
     * transitions. Runs under the processor's transition lock (see
     * [GeofenceBusinessTransitionProcessor.process]), so the check, the recorded ENTER fix and the
     * commit are one step that an interleaved callback cannot split. A callback without a fix, or
     * with no registration from this boot to order against, keeps the legacy behavior.
     *
     * @return false for an ENTER decided no later than an EXIT already handled, or an EXIT decided no
     * later than an ENTER already handled.
     */
    fun admitsNativeEdge(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        triggeringFixElapsedMs: Long?
    ): Boolean {
        val fix = triggeringFixElapsedMs ?: return true
        val region = store.getCachedRegion(geofenceId)?.takeIf { !it.isPolygon } ?: return true
        val incarnation = currentIncarnation(region)?.takeIf { fix >= it.registeredAtElapsedMs } ?: return true
        return when (transition) {
            Event.GeofenceTransition.ENTER -> {
                if (fix <= (incarnation.lastExitFixElapsedMs ?: Long.MIN_VALUE)) return false
                store.recordNativeEnterFix(geofenceId, incarnation.registeredAtElapsedMs, fix)
                true
            }
            Event.GeofenceTransition.EXIT -> fix > (incarnation.lastEnterFixElapsedMs ?: Long.MIN_VALUE)
            Event.GeofenceTransition.DWELL -> true
        }
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

    /**
     * The live registration, only when it was made in this boot. Its elapsed stamps can't order
     * against fixes from any other, and one with no boot stamp proves nothing. Either way the device
     * needs a fresh registration, which a boot change triggers (see `registrationsPredateBoot`).
     */
    private fun currentIncarnation(region: GeofenceRegion): GeofenceRegistrationIncarnation? =
        store.getRegistrationIncarnation(region.id)?.takeIf {
            it.regionRevision == region.transitionRevision() &&
                it.bootSessionId == bootSessionProvider.currentSessionId()
        }

    private fun currentVisit(region: GeofenceRegion, bootSessionId: String): GeofenceDwellVisit? {
        val visit = store.getDwellVisit(region.id) ?: return null
        val registrationChanged = !region.isPolygon &&
            (
                visit.registrationElapsedMs == null ||
                    visit.registrationElapsedMs != currentIncarnation(region)?.registeredAtElapsedMs
                )
        if (
            visit.regionRevision != region.transitionRevision() ||
            visit.userStateGeneration != store.userStateGeneration() ||
            registrationChanged ||
            // A reboot ended OS monitoring whether or not BOOT_COMPLETED reached this app.
            GeofenceVisitTiming.isFromAnotherBoot(visit, bootSessionId)
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

    private fun latestInsideFix(visit: GeofenceDwellVisit): Long? =
        listOfNotNull(visit.entryFixElapsedMs, visit.lastInsideFixElapsedMs).maxOrNull()

    /**
     * Latest time the visit is known inside. Without a fix, its ENTER's receipt: GMS decided it no
     * later, so outside proof after that receipt certainly follows the entry.
     */
    private fun insideAnchor(visit: GeofenceDwellVisit): Long =
        latestInsideFix(visit) ?: visit.enteredAtElapsedMs ?: Long.MAX_VALUE

    /**
     * Whether GMS itself turned inside after [outsideAt], so a DWELL it decided at [dwellFix] counts
     * its loitering delay from after that outside proof. A DWELL alone only bounds GMS's ENTER from
     * above, and GMS may have missed a departure only the SDK saw. GMS's state moved past the proof
     * when the registration came no earlier, when GMS reported an EXIT no earlier, or when an ENTER
     * between the proof and this DWELL was admitted.
     */
    private fun gmsReenteredSince(
        incarnation: GeofenceRegistrationIncarnation,
        outsideAt: Long?,
        dwellFix: Long
    ): Boolean {
        outsideAt ?: return true
        val lastEnterFix = incarnation.lastEnterFixElapsedMs
        return incarnation.registeredAtElapsedMs >= outsideAt ||
            (incarnation.lastExitFixElapsedMs ?: Long.MIN_VALUE) >= outsideAt ||
            (lastEnterFix != null && lastEnterFix > outsideAt && lastEnterFix <= dwellFix)
    }

    /**
     * Whether an ENTER that found containment outside is the observed crossing of [existing], a
     * visit a duplicate of the same ENTER wrote first. It must itself be an observed entry (no
     * outside proof since, see [observedCircleEntry]) with the visit's own entry fix, and the visit
     * must have reported nothing yet: a queued DWELL is not rewritten. A visit begun by an ENTER
     * with a later fix is not recovered, since that ENTER may follow a lost EXIT.
     */
    private fun recoversObservedEntry(
        existing: GeofenceDwellVisit,
        incarnation: GeofenceRegistrationIncarnation?,
        entryFixElapsedMs: Long?
    ): Boolean {
        val entryFix = entryFixElapsedMs ?: return false
        val existingEntryFix = existing.entryFixElapsedMs ?: return false
        return !existing.entryWasObserved &&
            !existing.emitted &&
            existing.dwellReservation == null &&
            entryFix == existingEntryFix &&
            observedCircleEntry(incarnation, entryFix)
    }

    private suspend fun emitIfDue(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        observedAtSeconds: Long,
        observedAtElapsedMs: Long,
        bootSessionId: String,
        detectionSource: String,
        nativeDwellProvesThreshold: Boolean = false
    ) {
        if (visit.emitted) return
        val reserved = if (visit.dwellReservation != null) {
            visit
        } else {
            reserveDwell(
                region,
                visit,
                observedAtSeconds,
                observedAtElapsedMs,
                bootSessionId,
                detectionSource,
                nativeDwellProvesThreshold
            ) ?: return
        }
        val reservation = reserved.dwellReservation ?: return
        val result = transitionProcessor.process(
            geofenceId = region.id,
            transition = Event.GeofenceTransition.DWELL,
            timestampSeconds = reservation.timestampSeconds,
            enforceConfiguredTransition = true,
            expectedRegionRevision = reserved.regionRevision,
            expectedUserStateGeneration = reserved.userStateGeneration,
            requireRegistered = true,
            visitContext = GeofenceTransitionEmitter.VisitContext.Dwell(
                visitId = reserved.visitId,
                enteredAt = reservation.enteredAt,
                thresholdSeconds = reservation.thresholdSeconds,
                durationSeconds = reservation.durationSeconds,
                detectionSource = reservation.detectionSource
            )
        )
        val staged = store.getAllPendingTransitionEntries().any {
            it.geofenceId == region.id && it.transition == Event.GeofenceTransition.DWELL && it.visitId == reserved.visitId
        }
        if (result == GeofenceTransitionEmitter.Result.PERSISTED || staged) {
            store.saveDwellVisit(reserved.copy(emitted = true))
        }
    }

    /**
     * Fixes the evidence this visit's DWELL reports, saved before the emission so that a retry or a
     * restart after a partial one (the outbox took the event but marking the visit emitted failed)
     * resends it unchanged. Null while the threshold has not passed, or if the save fails.
     */
    private fun reserveDwell(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        observedAtSeconds: Long,
        observedAtElapsedMs: Long,
        bootSessionId: String,
        detectionSource: String,
        nativeDwellProvesThreshold: Boolean
    ): GeofenceDwellVisit? {
        val thresholdSeconds = region.dwellThresholdSeconds
        val elapsedMs = GeofenceVisitTiming.elapsedMs(visit, bootSessionId, observedAtElapsedMs)
        // GMS emits DWELL only after its configured loitering delay. That proves the threshold even
        // when process death lost ENTER, but it does not reveal an observed entry time or duration.
        // Location evidence proves it only by time measured on the boot clock: wall age can step.
        if (!nativeDwellProvesThreshold && (elapsedMs == null || elapsedMs < thresholdSeconds * MILLIS_PER_SECOND)) {
            return null
        }
        // Reported only for an observed entry whose wall stamps still agree with the boot clock.
        // A delayed ENTER callback can make local elapsed time slightly short, so a duration under
        // the threshold is left absent rather than reported.
        val reportsEntry = visit.entryWasObserved &&
            elapsedMs != null &&
            GeofenceVisitTiming.wallClockAgrees(visit.enteredAtSeconds, observedAtSeconds, elapsedMs)
        val durationSeconds = elapsedMs?.div(MILLIS_PER_SECOND)
        val reserved = visit.copy(
            dwellReservation = GeofenceDwellReservation(
                timestampSeconds = observedAtSeconds,
                enteredAt = visit.enteredAtSeconds.takeIf { reportsEntry },
                thresholdSeconds = thresholdSeconds,
                durationSeconds = durationSeconds?.takeIf { reportsEntry && it >= thresholdSeconds },
                detectionSource = detectionSource
            )
        )
        return reserved.takeIf { store.saveDwellVisit(it) }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        val mutex = Mutex()
    }
}

internal fun GeofenceRegion.tracksVisit(): Boolean = dwellThresholdSeconds > 0
