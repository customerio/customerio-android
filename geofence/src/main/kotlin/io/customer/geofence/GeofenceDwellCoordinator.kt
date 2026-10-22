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
 * ends, describes, or emits a dwell for a visit.
 *
 * Visits are ordered and measured on the boot clock, never wall time (see [GeofenceVisitTiming]).
 */
internal class GeofenceDwellCoordinator(
    private val store: GeofenceRegionStore,
    private val transitionProcessor: GeofenceBusinessTransitionProcessor,
    private val clock: Clock,
    /** Null journals nothing. Before [bootSessionProvider], so a trailing provider lambda still binds to it. */
    private val diagnosticRecorder: DwellDiagnosticRecorder? = null,
    private val bootSessionProvider: PolygonBootSessionProvider
) {
    /**
     * The visit and reason [journalUnqualifiedEvidence] last journaled per fence, read and written under
     * [mutex]. In memory only: it bounds a parked device's per-sample evidence to one record per visit.
     */
    private val unqualifiedJournaled = mutableMapOf<String, Pair<String, DwellDiagnosticReason>>()

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
     * @param polygonEntryProvesInside polygons only. The ENTER's own fix lay inside the ring by more
     * than its accuracy, so it is inside proof as [onInsideEvidence] records it. A marginal arrival
     * committed by corroboration is not: it only says the device is probably inside.
     */
    suspend fun onEnter(
        geofenceId: String,
        enteredAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        beginsNewVisit: Boolean = false,
        entryFixElapsedMs: Long? = null,
        polygonOutsideObserved: Boolean = false,
        enteredAtElapsedMs: Long? = null,
        polygonEntryProvesInside: Boolean = false
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
                // A repeated polygon ENTER with a decisive fix is inside proof for the visit it repeats.
                if (region.isPolygon && polygonEntryProvesInside &&
                    startedAtElapsedMs > (existing.lastInsideFixElapsedMs ?: Long.MIN_VALUE)
                ) {
                    store.saveDwellVisit(existing.copy(lastInsideFixElapsedMs = startedAtElapsedMs))
                }
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
        // The stay this ENTER replaces may still owe a dwell the outbox refused at its EXIT.
        if (existing != null) {
            flushPendingReservation(
                region,
                existing,
                bootSessionId,
                enteredAtSeconds,
                startedAtElapsedMs,
                if (region.isPolygon) "location_evidence" else "native"
            )
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
                // A decisive polygon entry is the visit's first inside proof (see [capturePolygonExit]).
                lastInsideFixElapsedMs = startedAtElapsedMs.takeIf { region.isPolygon && polygonEntryProvesInside },
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
    ) = withLockAndDiagnosticToken withLock@{ diagnosticToken ->
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
        var visit = currentVisit(region, bootSessionId, diagnosticToken)
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
                journalNotAttributed(diagnosticToken, expectedUserStateGeneration, geofenceId)
                return@withLock
            }
            // GMS delivered dwell only after its loitering delay, so this callback is qualifying
            // proof even if the preceding ENTER callback was lost during process death. That holds
            // only for the live registration's delay: a DWELL from a replaced one proved some other
            // threshold, or geometry, and cannot recover a visit here.
            if (fix < incarnation.registeredAtElapsedMs) return@withLock
            // The ended stay may still owe a dwell the outbox refused at its EXIT.
            visit?.let { ended -> flushPendingReservation(region, ended, bootSessionId, observedAtSeconds, atElapsedMs, "native") }
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
        if (!belongsTo(visit, triggeringFixElapsedMs)) {
            journalNotAttributed(diagnosticToken, expectedUserStateGeneration, geofenceId)
            return@withLock
        }
        // belongsTo guarantees a fix. Recorded even once emitted: the device is inside at this fix,
        // so only outside proof after it can end the visit (see [departedSince] and above).
        val insideFix = triggeringFixElapsedMs ?: return@withLock
        if (insideFix > (visit.lastInsideFixElapsedMs ?: Long.MIN_VALUE)) {
            visit = visit.copy(lastInsideFixElapsedMs = insideFix)
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        // GMS fires DWELL within seconds of re-adding a fence the device is inside, whatever the
        // loitering delay (seen on GMS 26.37: a threshold or geometry change re-adds under the same id).
        // A genuine one cannot come sooner than the threshold after the registration it loitered on was
        // added, so an earlier one is still inside evidence but proves no threshold. Judged on GMS's fix,
        // never the receipt: a delayed delivery must not turn an early callback into proof, and GMS
        // re-serves a still device's last position with a fresh stamp, so a genuine one's fix is current.
        val thresholdSinceRegistrationMs = insideFix - incarnation.registeredAtElapsedMs
        if (thresholdSinceRegistrationMs < region.dwellThresholdSeconds * MILLIS_PER_SECOND - REGISTRATION_STAMP_SLACK_MS) {
            recordDwellOutcome(
                diagnosticToken,
                expectedUserStateGeneration,
                DwellDiagnosticEvent.DWELL_REJECTED,
                geofenceId,
                DwellDiagnosticReason.BELOW_THRESHOLD
            )
            return@withLock
        }
        emitIfDue(
            region = region,
            visit = visit,
            observedAtSeconds = observedAtSeconds,
            observedAtElapsedMs = atElapsedMs,
            bootSessionId = bootSessionId,
            detectionSource = "native",
            nativeDwellProvesThreshold = true,
            diagnosticToken = diagnosticToken
        )
    }

    /**
     * Called only with a fresh, decisive inside fix for a real polygon boundary. The fix is recorded
     * as the visit's latest inside proof whatever the polygon configures, so its EXIT can tell how
     * long before the departure the device was last seen inside (see [capturePolygonExit]); it
     * qualifies a dwell only for a polygon with a threshold.
     * @param observedAtElapsedMs the fix's elapsedRealtime; null means now.
     * @param isStillCurrent re-checked once this holds the lock: a teardown the caller's pass did not
     *   see may land while it waits, and keeps the user generation, so that check alone misses it.
     */
    suspend fun onInsideEvidence(
        geofenceId: String,
        observedAtSeconds: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        observedAtElapsedMs: Long? = null,
        isStillCurrent: () -> Boolean = { true }
    ) = withLockAndDiagnosticToken withLock@{ diagnosticToken ->
        if (store.userStateGeneration() != expectedUserStateGeneration || !isStillCurrent()) return@withLock
        val region = store.getCachedRegion(geofenceId) ?: return@withLock
        if (!region.isPolygon || !region.tracksVisit()) return@withLock
        val bootSessionId = bootSessionProvider.currentSessionId()
        val atElapsedMs = observedAtElapsedMs ?: clock.elapsedRealtime()
        var visit = currentVisit(region, bootSessionId, diagnosticToken)
        // Without a threshold, evidence only dates a visit an ENTER began; it starts none.
        if (visit == null && region.dwellThresholdSeconds <= 0) return@withLock
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
        if (atElapsedMs < (visit.enteredAtElapsedMs ?: atElapsedMs)) {
            journalNotAttributed(diagnosticToken, expectedUserStateGeneration, geofenceId)
            return@withLock
        }
        // The latest time the device was decisively seen inside, which bounds how early a later
        // departure could have been. Recorded even once emitted, for the EXIT's duration.
        if (atElapsedMs > (visit.lastInsideFixElapsedMs ?: Long.MIN_VALUE)) {
            visit = visit.copy(lastInsideFixElapsedMs = atElapsedMs)
            if (!store.saveDwellVisit(visit)) return@withLock
        }
        // Once this visit emitted, later evidence cannot turn it back into a candidate. In
        // particular, a long evidence gap after emission must not reset `emitted` and create a
        // second dwell for the same continuous visit.
        if (region.dwellThresholdSeconds <= 0 || visit.emitted) return@withLock
        emitIfDue(
            region,
            visit,
            observedAtSeconds,
            atElapsedMs,
            bootSessionId,
            "location_evidence",
            diagnosticToken = diagnosticToken
        )
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
     * Reads the visit a polygon EXIT's fix ends, before the EXIT is committed. The processor's await
     * can let a newer visit be written, so [onPolygonExit] removes only the visit read here and the
     * EXIT describes only it.
     *
     * @param exitFixElapsedMs the deciding fix's elapsedRealtime, read with [exitedAtSeconds].
     */
    suspend fun capturePolygonExit(
        geofenceId: String,
        exitedAtSeconds: Long,
        exitFixElapsedMs: Long,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ): PolygonExit = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock PolygonExit()
        val region = store.getCachedRegion(geofenceId)?.takeIf { it.isPolygon } ?: return@withLock PolygonExit()
        val bootSessionId = bootSessionProvider.currentSessionId()
        val visit = currentVisit(region, bootSessionId) ?: return@withLock PolygonExit()
        // A visit begun after this fix is a newer stay. Ordered on the boot clock, so a wall clock
        // stepped back since the entry cannot keep the visit. An unstamped legacy visit has no
        // trustworthy ordering; this decisive outside fix ends it without reporting its duration.
        val startedAfterExit = visit.enteredAtElapsedMs?.let { exitFixElapsedMs < it }
            ?: false
        if (startedAfterExit) return@withLock PolygonExit()
        val dwellDurable = flushPendingReservation(region, visit, bootSessionId, exitedAtSeconds, exitFixElapsedMs, "location_evidence")
        // An outside fix older than inside proof the visit already holds (the engine's ordering of the
        // two was lost with a restart): the stay ends, but its span would include time inside after
        // this fix, so no timing is reported rather than a span with a falsely certain end.
        val predatesInsideProof = latestInsideFix(visit)?.let { exitFixElapsedMs < it } == true
        if (predatesInsideProof) {
            return@withLock PolygonExit(
                endedVisitId = visit.visitId.takeIf { dwellDurable },
                visitContext = GeofenceTransitionEmitter.VisitContext.Exit(
                    visitId = visit.visitId,
                    enteredAt = null,
                    durationSeconds = null,
                    detectionSource = "location_evidence"
                )
            )
        }
        PolygonExit(
            // A reservation the outbox refused keeps its visit for the launch sweep to retry.
            endedVisitId = visit.visitId.takeIf { dwellDurable },
            visitContext = exitContext(
                visit,
                bootSessionId,
                currentIncarnation(region)?.outsideProvenAtElapsedMs,
                exitedAtSeconds,
                exitFixElapsedMs,
                exitDecidedAtElapsedMs = exitFixElapsedMs,
                "location_evidence",
                // A polygon departure is noticed by the first decisive outside fix, not when it
                // happened; the gap since the last inside proof is reported as the uncertainty.
                departureNoEarlierThanElapsedMs = latestInsideFix(visit) ?: visit.enteredAtElapsedMs
            )
        )
    }

    /**
     * Ends the visit [capturePolygonExit] read, once its EXIT is committed. A visit written since has
     * another ID and is a newer stay. Wall time plays no part: after a backward clock step a wall-time
     * cleanup would keep the ended visit, and the next inside fix could then resume it across the time
     * spent outside.
     */
    suspend fun onPolygonExit(
        geofenceId: String,
        exitFixElapsedMs: Long,
        endedVisitId: String?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        endedVisitId ?: return@withLock
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        val region = store.getCachedRegion(geofenceId)?.takeIf { it.isPolygon } ?: return@withLock
        val visit = currentVisit(region, bootSessionProvider.currentSessionId()) ?: return@withLock
        if (visit.visitId != endedVisitId) return@withLock
        if (visit.enteredAtElapsedMs?.let { exitFixElapsedMs < it } == true) return@withLock
        store.removeDwellVisit(geofenceId)
    }

    /**
     * Identifies and describes the visit a native EXIT provably belongs to (see [exitContext]). Both
     * are read under this lock, so a re-entry handled before the EXIT commits cannot be described in
     * its place. [onNativeExitHandled] ends the captured visit after containment records the departure.
     *
     * An EXIT that cannot be attributed leaves the visit alone. It may belong to a replaced
     * registration or an earlier visit, and a genuine departure is followed by a fresh ENTER, which
     * restarts the visit once containment has recorded the exit.
     *
     * @param exitedAtElapsedMs elapsedRealtime read with [exitedAtSeconds]; null means now.
     */
    suspend fun onNativeExit(
        geofenceId: String,
        exitedAtSeconds: Long,
        triggeringFixElapsedMs: Long?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        exitedAtElapsedMs: Long? = null
    ): NativeExit = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock NativeExit(current = true)
        val region = store.getCachedRegion(geofenceId) ?: return@withLock NativeExit(current = true)
        val incarnation = currentIncarnation(region)
        val bootSessionId = bootSessionProvider.currentSessionId()
        val visit = currentVisit(region, bootSessionId)
        // GMS decided this EXIT no later than a fix that placed the device inside, this visit's or a
        // newer ENTER's, so it describes a departure already overruled. It must not end the visit or
        // move containment.
        val latestInsideFix = listOfNotNull(visit?.let(::latestInsideFix), incarnation?.lastEnterFixElapsedMs).maxOrNull()
        if (triggeringFixElapsedMs != null && latestInsideFix != null && triggeringFixElapsedMs <= latestInsideFix) {
            if (incarnation != null && triggeringFixElapsedMs >= incarnation.registeredAtElapsedMs) {
                // Still a fix this registration judged outside, so no ENTER decided before it can
                // later count as the start of the current stay.
                store.raiseOutsideProof(setOf(geofenceId), triggeringFixElapsedMs, bootSessionId)
            }
            // Decided at or after the visit's entry (a tie is ambiguous), so the device left
            // after that entry and came back unobserved. The visit, its queued DWELL and its
            // inside evidence stand; only the entry no longer dates the current stay.
            if (visit != null && visit.entryWasObserved && belongsTo(visit, triggeringFixElapsedMs)) {
                store.saveDwellVisit(visit.copy(entryWasObserved = false))
            }
            return@withLock NativeExit(current = false)
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
        visit ?: return@withLock NativeExit(current = true)
        // Received before the visit began, so it describes an earlier stay. Compared on the boot
        // clock: a wall clock stepped back since the ENTER must not keep the visit this EXIT ended.
        val receivedAtElapsedMs = exitedAtElapsedMs ?: clock.elapsedRealtime()
        val receivedBeforeVisit = visit.enteredAtElapsedMs?.let { receivedAtElapsedMs < it }
            ?: (exitedAtSeconds < visit.enteredAtSeconds)
        if (receivedBeforeVisit) return@withLock NativeExit(current = true)
        if (!belongsTo(visit, triggeringFixElapsedMs)) {
            // The visit stays: a stale callback must not end it, and its emitted mark keeps a
            // redelivered DWELL from repeating. But this EXIT is still delivered and moves
            // containment outside, so no later DWELL may date the stay from an entry before it.
            if (visit.entryWasObserved && geofenceId in store.getEnteredIds()) {
                store.saveDwellVisit(visit.copy(entryWasObserved = false))
            }
            return@withLock NativeExit(current = true)
        }
        val dwellDurable = flushPendingReservation(region, visit, bootSessionId, exitedAtSeconds, receivedAtElapsedMs, "native")
        NativeExit(
            current = true,
            // A reservation the outbox refused keeps its visit for the launch sweep to retry.
            endedVisitId = visit.visitId.takeIf { dwellDurable },
            // The proof as it stood before this EXIT recorded its own fix.
            visitContext = exitContext(
                visit,
                bootSessionId,
                incarnation?.outsideProvenAtElapsedMs,
                exitedAtSeconds,
                receivedAtElapsedMs,
                // belongsTo guarantees the fix; the fallback only satisfies the type.
                exitDecidedAtElapsedMs = triggeringFixElapsedMs ?: receivedAtElapsedMs,
                "native",
                // GMS samples location sparsely, minutes apart when still, so the departure may be
                // any time since the device was last known inside, as for a polygon.
                departureNoEarlierThanElapsedMs = latestInsideFix(visit) ?: visit.enteredAtElapsedMs
            )
        )
    }

    /** Ends only the captured visit, once the native EXIT has moved containment outside. */
    suspend fun onNativeExitHandled(
        geofenceId: String,
        endedVisitId: String,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = mutex.withLock {
        if (store.userStateGeneration() != expectedUserStateGeneration) return@withLock
        // A rejected EXIT leaves containment inside. Keep the visit so the next ENTER can order
        // itself against this EXIT's outside proof (see [departedSince]).
        if (geofenceId in store.getEnteredIds()) return@withLock
        val region = store.getCachedRegion(geofenceId)?.takeIf { !it.isPolygon } ?: return@withLock
        val visit = currentVisit(region, bootSessionProvider.currentSessionId()) ?: return@withLock
        if (visit.visitId != endedVisitId) return@withLock
        store.removeDwellVisit(geofenceId)
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

    /** With a [diagnosticToken], journals the unemitted candidate this read ends; without one, journals nothing. */
    private fun currentVisit(
        region: GeofenceRegion,
        bootSessionId: String,
        diagnosticToken: Long? = null
    ): GeofenceDwellVisit? {
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
            // At the visit's own generation, so one from an earlier session is never journaled into this one.
            if (!visit.emitted && region.dwellThresholdSeconds > 0) {
                recordDwellOutcome(
                    diagnosticToken,
                    visit.userStateGeneration,
                    DwellDiagnosticEvent.DWELL_REJECTED,
                    region.id,
                    DwellDiagnosticReason.CONTINUITY_LOST
                )
            }
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

    /**
     * What an EXIT reports about the visit it ended. The visit's ID is reported whenever the EXIT is
     * attributed to it, even when its entry was never observed (recovered from a DWELL, found inside
     * at registration, or withdrawn by a later EXIT or ENTER), so the EXIT pairs with any DWELL that
     * visit reported. Null only when outside proof follows everything that placed the device inside
     * the visit, as [onNativeDwell] judges it: that stay already ended, and this EXIT may end a later
     * one the SDK never saw, which must not borrow this visit's ID.
     *
     * Entry time and duration are reported together or not at all (see [observedDurationSeconds]).
     *
     * @param outsideProvenAtElapsedMs the live registration's outside proof. Identity compares it with
     * [insideAnchor], timing with the entry: a proof handled after a later DWELL can still predate
     * that DWELL's fix, so the visit continued but its span would include time away.
     * @param exitDecidedAtElapsedMs the fix this EXIT was decided on, which ends the reported span:
     * GMS's triggering fix for a native EXIT, the outside fix itself for a polygon.
     * @param departureNoEarlierThanElapsedMs the earliest the departure could have happened, on the
     * boot clock; null when unknown, which reports the whole span as uncertain. The gap up to
     * [exitDecidedAtElapsedMs] is reported with the duration, never used to withhold it. Neither a
     * circle's GMS EXIT nor a polygon's first decisive outside fix is the moment of departure: GMS
     * samples location sparsely, so the gap runs from the latest inside fix the visit holds (its
     * entry fix, a native DWELL's or a decisive polygon fix), or the entry when it holds none.
     */
    private fun exitContext(
        visit: GeofenceDwellVisit,
        bootSessionId: String,
        outsideProvenAtElapsedMs: Long?,
        exitedAtSeconds: Long,
        exitedAtElapsedMs: Long,
        exitDecidedAtElapsedMs: Long,
        detectionSource: String,
        departureNoEarlierThanElapsedMs: Long?
    ): GeofenceTransitionEmitter.VisitContext.Exit? {
        if (outsideProvenAtElapsedMs != null && outsideProvenAtElapsedMs > insideAnchor(visit)) return null
        val span = observedSpan(
            visit,
            bootSessionId,
            outsideProvenAtElapsedMs,
            exitedAtSeconds,
            exitedAtElapsedMs,
            exitDecidedAtElapsedMs
        )
        val uncertaintySeconds = span?.let { observed ->
            departureNoEarlierThanElapsedMs
                // Rounded up: rounding down would put the true stay outside the reported bound.
                ?.let { ((exitDecidedAtElapsedMs - it + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceIn(0L, observed.durationSeconds) }
                ?: observed.durationSeconds
        }
        return GeofenceTransitionEmitter.VisitContext.Exit(
            visitId = visit.visitId,
            enteredAt = span?.enteredAtSeconds,
            durationSeconds = span?.durationSeconds,
            detectionSource = detectionSource,
            durationUncertaintySeconds = uncertaintySeconds
        )
    }

    /**
     * Emits a dwell this visit reserved but never durably staged, before the EXIT ends the visit:
     * the process died, or the outbox refused the write, between the reservation and the staging.
     * Without this the EXIT would take the qualified occurrence with it, since a circle gets no
     * second native DWELL to retry it and a polygon no further inside evidence once outside. The
     * caller commits its EXIT only after this returns, so the two stay in order.
     *
     * @return whether the visit owes no dwell any more: none was reserved, or the reservation is
     * now in the outbox or staged for recovery. False keeps the visit, for [recoverPendingReservations].
     */
    private suspend fun flushPendingReservation(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        bootSessionId: String,
        observedAtSeconds: Long,
        observedAtElapsedMs: Long,
        detectionSource: String
    ): Boolean {
        if (visit.emitted || visit.dwellReservation == null) return true
        emitIfDue(region, visit, observedAtSeconds, observedAtElapsedMs, bootSessionId, detectionSource)
        return store.getDwellVisit(region.id)?.takeIf { it.visitId == visit.visitId }?.emitted == true
    }

    /**
     * At launch, emits every dwell reserved but never durably staged: the process died between a
     * reservation and its staging, or an EXIT found the outbox refusing the write and kept the
     * visit. Nothing else revisits such a reservation: GMS sends a circle no second DWELL, and a
     * polygon gets no inside evidence once the device has left. A visit that no longer holds is
     * dropped as [currentVisit] drops it. A reservation older than [MAX_RESERVATION_AGE_SECONDS] is
     * given up rather than delivered: so late, it would trigger campaigns for a stay long over.
     */
    suspend fun recoverPendingReservations() = mutex.withLock {
        val bootSessionId = bootSessionProvider.currentSessionId()
        store.getDwellVisits().forEach { stored ->
            val reservation = stored.dwellReservation ?: return@forEach
            if (stored.emitted) return@forEach
            val region = store.getCachedRegion(stored.geofenceId) ?: return@forEach
            val visit = currentVisit(region, bootSessionId) ?: return@forEach
            emitIfDue(
                region,
                visit,
                reservation.timestampSeconds,
                clock.elapsedRealtime(),
                bootSessionId,
                reservation.detectionSource
            )
        }
        // Visits a replacement or removal dropped while they still owed their dwell. The stay is
        // over, so the only checks are the processor's own: the fence's current revision, the user
        // generation and routability. Only a failed outbox write is worth another launch; any other
        // refusal is final, or a fence routed again weeks later would get a DWELL with a stale date.
        store.getOrphanedDwellReservations().forEach { orphan ->
            val region = store.getCachedRegion(orphan.geofenceId)
            val deliverable = region != null &&
                orphan.regionRevision == region.transitionRevision() &&
                orphan.userStateGeneration == store.userStateGeneration() &&
                orphan.dwellReservation?.let(::isTooOldToDeliver) != true
            if (!deliverable || deliverReservation(region, orphan) != ReservationDelivery.RETRY) {
                store.removeOrphanedDwellReservation(orphan.visitId)
            }
        }
    }

    /** On the wall clock, the only one a reservation from an earlier boot can be compared on. */
    private fun isTooOldToDeliver(reservation: GeofenceDwellReservation): Boolean =
        clock.currentTimeSeconds() - reservation.timestampSeconds > MAX_RESERVATION_AGE_SECONDS

    /** The entry time and duration an EXIT reports, dated at the fixes the transitions were decided on. */
    private data class ObservedSpan(val enteredAtSeconds: Long, val durationSeconds: Long)

    /**
     * The span from an observed entry to this EXIT, or null when unknown. That needs an observed entry
     * (not one recovered from a DWELL, rebuilt after continuity loss, or withdrawn by a later EXIT)
     * timed in this boot, and no outside proof since that entry, which would put time away in the
     * span. The whole-second wall span is reported only while it agrees with the boot clock (see
     * [GeofenceVisitTiming.wallClockAgrees]): a clock step in between would otherwise invent or erase
     * time.
     *
     * Both ends are dated at the fix the transition was decided on, not at the callback's receipt:
     * GMS can deliver an ENTER or EXIT long after its fix, and a receipt-to-receipt span would hide
     * that delay inside the stay. The visit's stamps stay on receipt, which orders callbacks; only
     * the reported values shift, by delays measured on the boot clock.
     *
     * @param exitDecidedAtElapsedMs the fix this EXIT was decided on; [exitedAtElapsedMs] is its receipt.
     */
    private fun observedSpan(
        visit: GeofenceDwellVisit,
        bootSessionId: String,
        outsideProvenAtElapsedMs: Long?,
        exitedAtSeconds: Long,
        exitedAtElapsedMs: Long,
        exitDecidedAtElapsedMs: Long
    ): ObservedSpan? {
        if (!visit.entryWasObserved) return null
        val enteredAtElapsedMs = visit.enteredAtElapsedMs ?: return null
        val entryDecidedAtElapsedMs = visit.entryFixElapsedMs ?: enteredAtElapsedMs
        if (outsideProvenAtElapsedMs != null && outsideProvenAtElapsedMs > entryDecidedAtElapsedMs) return null
        val elapsedMs = GeofenceVisitTiming.elapsedMs(visit, bootSessionId, exitedAtElapsedMs) ?: return null
        val receiptSpanSeconds = exitedAtSeconds - visit.enteredAtSeconds
        if (receiptSpanSeconds < 0 || !GeofenceVisitTiming.wallClockAgrees(visit.enteredAtSeconds, exitedAtSeconds, elapsedMs)) {
            return null
        }
        val entryDelaySeconds = ((enteredAtElapsedMs - entryDecidedAtElapsedMs) / MILLIS_PER_SECOND).coerceAtLeast(0L)
        val exitDelaySeconds = ((exitedAtElapsedMs - exitDecidedAtElapsedMs) / MILLIS_PER_SECOND).coerceAtLeast(0L)
        return ObservedSpan(
            enteredAtSeconds = visit.enteredAtSeconds - entryDelaySeconds,
            durationSeconds = (receiptSpanSeconds + entryDelaySeconds - exitDelaySeconds).coerceAtLeast(0L)
        )
    }

    /**
     * [mutex], with the diagnostic token read before its await. Callers label the block `withLock@`, so their
     * bodies return as they did under [withLock]. A clear that retires the token while the call waits for the
     * lock, or later awaits the processor, drops every record the call makes.
     */
    private suspend inline fun <T> withLockAndDiagnosticToken(block: (diagnosticToken: Long?) -> T): T {
        val diagnosticToken = diagnosticRecorder?.currentToken()
        return mutex.withLock { block(diagnosticToken) }
    }

    /**
     * Journals a dwell outcome for the session at [expectedUserStateGeneration]. Records nothing without a
     * recorder or token, or once that generation has moved on. Never suspends, logs or calls customer code, so
     * it runs under [mutex], and a diagnostics failure leaves the decision that reached it unchanged.
     */
    private fun recordDwellOutcome(
        diagnosticToken: Long?,
        expectedUserStateGeneration: Long,
        event: DwellDiagnosticEvent,
        geofenceId: String,
        reason: DwellDiagnosticReason? = null,
        dueElapsedMs: Long? = null
    ) {
        val recorder = diagnosticRecorder ?: return
        val token = diagnosticToken ?: return
        try {
            if (store.userStateGeneration() != expectedUserStateGeneration) return
            recorder.record(
                DwellDiagnosticRecord(
                    event = event,
                    atElapsedMs = clock.elapsedRealtime(),
                    fenceId = geofenceId,
                    dueElapsedMs = dueElapsedMs,
                    reason = reason
                ),
                token
            )
        } catch (e: Exception) {
            // Diagnostics only: the outcome stands whether or not it is journaled.
        }
    }

    /** Evidence, or a native DWELL, that cannot be tied to the current stay, so it proves nothing for its dwell. */
    private fun journalNotAttributed(diagnosticToken: Long?, expectedUserStateGeneration: Long, geofenceId: String) {
        recordDwellOutcome(
            diagnosticToken,
            expectedUserStateGeneration,
            DwellDiagnosticEvent.DWELL_REJECTED,
            geofenceId,
            DwellDiagnosticReason.NOT_ATTRIBUTED
        )
    }

    /**
     * Why location evidence reserved no dwell, classified from the inputs [reserveDwell] decides on: before the
     * threshold, or with no duration on this boot's clock. A qualified visit whose reservation failed to save
     * is a storage outcome, so it is not journaled. Once per visit and reason, as a parked device reports
     * inside evidence on every sample.
     */
    private fun journalUnqualifiedEvidence(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        bootSessionId: String,
        observedAtElapsedMs: Long,
        diagnosticToken: Long?
    ) {
        if (diagnosticRecorder == null || diagnosticToken == null) return
        val thresholdMs = region.dwellThresholdSeconds * MILLIS_PER_SECOND
        val elapsedMs = GeofenceVisitTiming.elapsedMs(visit, bootSessionId, observedAtElapsedMs)
        val reason = when {
            elapsedMs == null -> DwellDiagnosticReason.UNTIMED
            elapsedMs < thresholdMs -> DwellDiagnosticReason.BELOW_THRESHOLD
            else -> return
        }
        val journaled = visit.visitId to reason
        if (unqualifiedJournaled[region.id] == journaled) return
        if (unqualifiedJournaled.size >= MAX_UNQUALIFIED_JOURNALED) unqualifiedJournaled.clear()
        unqualifiedJournaled[region.id] = journaled
        recordDwellOutcome(
            diagnosticToken,
            visit.userStateGeneration,
            DwellDiagnosticEvent.DWELL_REJECTED,
            region.id,
            reason,
            elapsedMs?.let { observedAtElapsedMs - it + thresholdMs }
        )
    }

    /** When [visit] reaches its threshold, on the timeline [reserveDwell] measures; null when unmeasurable. */
    private fun dueElapsedMs(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        bootSessionId: String,
        atElapsedMs: Long
    ): Long? = GeofenceVisitTiming.elapsedMs(visit, bootSessionId, atElapsedMs)
        ?.let { atElapsedMs - it + region.dwellThresholdSeconds * MILLIS_PER_SECOND }

    private suspend fun emitIfDue(
        region: GeofenceRegion,
        visit: GeofenceDwellVisit,
        observedAtSeconds: Long,
        observedAtElapsedMs: Long,
        bootSessionId: String,
        detectionSource: String,
        nativeDwellProvesThreshold: Boolean = false,
        diagnosticToken: Long? = null
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
            ) ?: run {
                // Native proof reserves unless its save fails, a storage outcome rather than a rejection.
                if (!nativeDwellProvesThreshold) {
                    journalUnqualifiedEvidence(region, visit, bootSessionId, observedAtElapsedMs, diagnosticToken)
                }
                return
            }
        }
        val reservation = reserved.dwellReservation ?: return
        // Enforced here, where every path delivers: a DWELL the outbox kept refusing for a day is given
        // up, not delivered for a stay long over. Marked emitted so the visit cannot qualify again, and
        // so an EXIT or replacement waiting on it can proceed.
        if (isTooOldToDeliver(reservation)) {
            store.saveDwellVisit(reserved.copy(emitted = true))
            recordDwellOutcome(
                diagnosticToken,
                reserved.userStateGeneration,
                DwellDiagnosticEvent.DWELL_REJECTED,
                region.id,
                DwellDiagnosticReason.RESERVATION_EXPIRED
            )
            return
        }
        if (processReservation(region, reserved)) {
            store.saveDwellVisit(reserved.copy(emitted = true))
            // A durable on-device fact from here: the outbox persisted this DWELL, or the processor staged it
            // for recovery. Backend delivery is not implied, and whether the mark above saved changes neither
            // fact; an unmarked reservation processed again is journaled again.
            recordDwellOutcome(
                diagnosticToken,
                reserved.userStateGeneration,
                DwellDiagnosticEvent.DWELL_EMITTED,
                region.id,
                // Native proof can start a recovered visit at the proof itself, so no due time follows from it.
                dueElapsedMs = dueElapsedMs(region, reserved, bootSessionId, observedAtElapsedMs)
                    ?.takeUnless { nativeDwellProvesThreshold }
            )
        }
    }

    /**
     * Hands [reserved]'s reservation to the processor and reports whether the DWELL is now a durable
     * on-device fact: the outbox persisted it, or the processor staged it for recovery. Backend
     * delivery is not implied. The reservation is resent unchanged on every call, so a retry after a
     * partial emission stays idempotent downstream.
     */
    private suspend fun processReservation(region: GeofenceRegion, reserved: GeofenceDwellVisit): Boolean =
        deliverReservation(region, reserved) == ReservationDelivery.DELIVERED

    private enum class ReservationDelivery {
        /** Persisted to the outbox, or staged for its recovery. */
        DELIVERED,

        /** The outbox write failed: the same DWELL can be tried again. */
        RETRY,

        /** Refused for good: the fence, revision, routing or user no longer match. */
        REFUSED
    }

    private suspend fun deliverReservation(region: GeofenceRegion, reserved: GeofenceDwellVisit): ReservationDelivery {
        val reservation = reserved.dwellReservation ?: return ReservationDelivery.REFUSED
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
        return when {
            result == GeofenceTransitionEmitter.Result.PERSISTED || staged -> ReservationDelivery.DELIVERED
            result == GeofenceTransitionEmitter.Result.PERSIST_FAILED -> ReservationDelivery.RETRY
            else -> ReservationDelivery.REFUSED
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
        // The entry is dated at its fix, as the EXIT dates it (see observedSpan): a delayed ENTER
        // callback would otherwise shorten the stay. A duration still under the threshold is left
        // absent rather than reported.
        val reportsEntry = visit.entryWasObserved &&
            elapsedMs != null &&
            GeofenceVisitTiming.wallClockAgrees(visit.enteredAtSeconds, observedAtSeconds, elapsedMs)
        val entryDelaySeconds = listOfNotNull(visit.enteredAtElapsedMs, visit.entryFixElapsedMs)
            .takeIf { it.size == 2 }
            ?.let { (receipt, fix) -> ((receipt - fix) / MILLIS_PER_SECOND).coerceAtLeast(0L) }
            ?: 0L
        val durationSeconds = elapsedMs?.let { it / MILLIS_PER_SECOND + entryDelaySeconds }
        val reserved = visit.copy(
            dwellReservation = GeofenceDwellReservation(
                timestampSeconds = observedAtSeconds,
                enteredAt = (visit.enteredAtSeconds - entryDelaySeconds).takeIf { reportsEntry },
                thresholdSeconds = thresholdSeconds,
                durationSeconds = durationSeconds?.takeIf { reportsEntry && it >= thresholdSeconds },
                detectionSource = detectionSource
            )
        )
        return reserved.takeIf { store.saveDwellVisit(it) }
    }

    /**
     * What a native EXIT meant for its visit.
     *
     * @property current false when the EXIT is older than evidence already placing the device inside.
     * @property endedVisitId the attributed visit to end after processing, even when its context is null.
     * @property visitContext the visit this EXIT ended: always its ID, with entry time and duration
     * only when observed timing is known. Null when no visit is attributed, or outside proof shows the
     * visit had already ended. A current EXIT without one is still delivered.
     */
    internal data class NativeExit(
        val current: Boolean,
        val visitContext: GeofenceTransitionEmitter.VisitContext.Exit? = null,
        val endedVisitId: String? = null
    )

    /**
     * The visit a polygon EXIT ends, read by [capturePolygonExit].
     *
     * @property endedVisitId null when no visit of this boot was current at the EXIT's fix.
     * @property visitContext that visit's ID, with its entry time and duration when known.
     */
    internal data class PolygonExit(
        val endedVisitId: String? = null,
        val visitContext: GeofenceTransitionEmitter.VisitContext.Exit? = null
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * A registration is stamped once GMS confirms the add, which can come up to its 5 s call
         * timeout after GMS began loitering on it.
         */
        const val REGISTRATION_STAMP_SLACK_MS = 5_000L

        /**
         * How long a DWELL the outbox kept refusing may still be delivered. A day covers an outage the
         * device recovers from; after that the stay is history and a campaign it triggers would be wrong.
         */
        const val MAX_RESERVATION_AGE_SECONDS = 24L * 60 * 60

        // Above the 100 geofences GMS allows per app, so a clear never drops a live fence's mark in practice.
        const val MAX_UNQUALIFIED_JOURNALED = 128
        val mutex = Mutex()
    }
}

internal fun GeofenceRegion.tracksVisit(): Boolean =
    dwellThresholdSeconds > 0 || transitionTypes.contains(GeofenceTransitionType.EXIT)
