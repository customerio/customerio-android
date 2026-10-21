package io.customer.geofence.polygon

import android.annotation.SuppressLint
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticReason
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.DwellDiagnosticRecorder
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceRegistrar
import io.customer.geofence.GeofenceRegistrationGate
import io.customer.geofence.equalsForRegistration
import io.customer.geofence.store.GeofenceDwellVisit
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What one arm attempt did, for tests and diagnostics. */
internal enum class PolygonDwellWakeArm {
    NOT_PENDING,
    RECORDS_UNKNOWN,
    ALREADY_ATTEMPTED,
    RECORD_NOT_WRITTEN,
    ARMED,
    ARM_FAILED,
    SUPERSEDED
}

/** What a restore pass left for the registration holding the gate. */
internal sealed interface PolygonDwellWakeRestore {
    /** The records are unreadable, so any polygon may still carry a wake: re-add every one. */
    data object Unknown : PolygonDwellWakeRestore

    /**
     * Polygons whose registration may still carry a wake no longer wanted, because undoing it failed
     * or could not run, or because the pass's incoming selection drops the polygon or re-shapes its
     * circle. The caller re-adds or removes each normally, not as unchanged, then calls
     * `forgetHoldingGate` with the ones GMS confirmed. Wakes still wanted are not listed.
     */
    data class Known(val needingBaseline: Set<String>) : PolygonDwellWakeRestore
}

/** What a native DWELL on a polygon's enclosing circle may do; see `admitNativeWake`. */
internal sealed interface PolygonDwellWakeAdmission {
    /** The wake armed for [visitId], still that polygon's pending visit: it may look for proof. */
    data class Admitted(val visitId: String) : PolygonDwellWakeAdmission

    /**
     * A record no longer matching a pending visit, opt-out included, or one armed before this
     * process's latest fence, which that fence's restore owns: the caller settles it. The refusal
     * deletes nothing.
     */
    data object Stale : PolygonDwellWakeAdmission

    /**
     * No readable record names the polygon, so nothing can privilege the wake. Unreadable records
     * are left to the registration's restore, which re-adds every polygon.
     */
    data object Untracked : PolygonDwellWakeAdmission
}

/**
 * Experimental, off by default. Re-adds a pending polygon visit's enclosing circle with a native
 * DWELL due when the visit is, only to wake the app to look for polygon proof. The wake proves
 * nothing: dwell still needs the coordinator's precise inside fix, and this never starts a visit or
 * emits.
 *
 * One arm per visit, in this process and, through the write-ahead [records], across processes: a
 * re-add may restart GMS's loitering clock, so arming on every evaluation could postpone the wake
 * indefinitely. The due time comes from the original entry, so a later arm never moves it.
 *
 * Every GMS call runs under [gate], shared with repository registration, so a superseded arm is
 * restored before a newer registration can land. Only the `HoldingGate` members may be called with
 * the gate held; they never take it.
 */
internal class PolygonDwellWakeOwner(
    private val store: GeofenceRegionStore,
    private val registrar: GeofenceRegistrar,
    private val records: PolygonDwellWakeRecordStore,
    private val gate: GeofenceRegistrationGate,
    private val secureUserStore: SecureUserStore,
    private val bootSessionProvider: PolygonBootSessionProvider,
    private val enabled: () -> Boolean,
    private val elapsedRealtimeMs: () -> Long,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
    graceMs: Long = DEFAULT_GRACE_MS,
    /** Null records nothing; nothing recorded ever feeds a GMS or store decision. */
    private val diagnostics: DwellDiagnosticRecorder? = null
) {
    private val graceMs = graceMs.coerceAtLeast(0L)
    private val lock = Any()
    private var epoch = 0L
    private val attemptedVisitIds = mutableSetOf<String>()

    /** Visit id to the epoch its wake was armed in, by this process. */
    private val armedEpochs = mutableMapOf<String, Long>()

    /**
     * Visit id to the epoch GMS answered its arm in, no fence between. Only these may be forgotten
     * after an OS loss: a timed-out, superseded or other process's add may land after one.
     */
    private val confirmedEpochs = mutableMapOf<String, Long>()

    private data class PendingVisit(
        val region: GeofenceRegion,
        val visit: GeofenceDwellVisit,
        val bootSessionId: String,
        val enteredAtElapsedMs: Long
    )

    /**
     * For a polygon whose visit may await its dwell. Never awaits; the job is for a caller with an
     * OS execution window, and must never be joined while holding [gate].
     */
    fun onVisitPending(polygonId: String): Job? {
        if (!isEnabled()) return null
        val expectedEpoch = synchronized(lock) { epoch }
        return scope.launch(ioContext) { arm(polygonId, expectedEpoch) }
    }

    /**
     * Whether a native DWELL for [polygonId] is the wake armed for its pending visit: the record
     * matches that visit on this boot, generation and revision, with the experiment on, and no fence
     * in this process postdates its arm (see [wantedNow]). A query that launches nothing, writes
     * nothing and awaits no GMS, so a caller may run it under its own mutex.
     */
    fun admitNativeWake(polygonId: String): PolygonDwellWakeAdmission {
        val record = (records.read() as? PolygonDwellWakeRecords.Known)?.byPolygonId?.get(polygonId)
            ?: return PolygonDwellWakeAdmission.Untracked
        return if (wantedNow(record)) {
            PolygonDwellWakeAdmission.Admitted(record.visitId)
        } else {
            PolygonDwellWakeAdmission.Stale
        }
    }

    /**
     * A visit ended or emitted, or its wake was handled. Restores the circle once the visit is no
     * longer the pending one it was armed for. A wake that leaves the visit pending (early, or with
     * no fix proving the stay) keeps its registration and record: it was only a chance to look, the
     * coordinator still emits only on precise proof, and the record refuses the visit a second arm.
     */
    fun onVisitSettled(polygonId: String): Job =
        scope.launch(ioContext) { gate.withRegistration { restoreStaleHoldingGate(polygonId) } }

    /**
     * Every controller teardown, after its store wipe. Fences in-flight arms, then under [gate]
     * restores every wake armed before the fence, wanted or not, since some teardowns keep the visit.
     * Call it outside the controller's locks: an inline dispatcher would run the restore, and the
     * registrar's logging, under them. [alreadyFenced] when the caller ran [fence] under the lock its
     * wipe ran under: fencing again would supersede an arm the new state began since.
     */
    fun onTeardown(alreadyFenced: Boolean = false): Job {
        if (!alreadyFenced) fence()
        return scope.launch(ioContext) {
            gate.withRegistration { restoreHoldingGate(onlyPolygonId = null, keepOnlyArmedSinceFence = true) }
        }
    }

    /**
     * GMS itself dropped every geofence: it reported the loss, or the device rebooted. Forgets, with
     * no GMS call, each record whose arm GMS answered before [lossEpoch], the fence that saw the loss;
     * any other waits for a confirmed restore or baseline. A registration
     * can take [gate] before this queued cleanup and a pending visit then arm again: GMS holds that
     * wake, so its record stays for a restore. Not for an inference such as a package update, after
     * which GMS may still hold a wake: re-add every polygon normally, then call [forgetHoldingGate]
     * with null. Fences now unless the caller passes the epoch its own [fence] returned.
     */
    fun onOsRegistrationsLost(lossEpoch: Long = fence()): Job =
        scope.launch(ioContext) { gate.withRegistration { forgetArmedBeforeHoldingGate(lossEpoch) } }

    /** Unreadable records stay: one may carry a timed-out add that GMS applies after the loss. */
    private fun forgetArmedBeforeHoldingGate(lossEpoch: Long) {
        val known = records.read() as? PolygonDwellWakeRecords.Known ?: return
        val token = diagnostics?.currentToken()
        known.byPolygonId.values.filter { answeredBefore(it, lossEpoch) }.forEach { forget(it, token) }
    }

    /**
     * Caller holds [gate]. Restores normal registration for every record, or [onlyPolygonId]'s, no
     * longer wanted (see [wantedNow]). On [PolygonDwellWakeRestore.Unknown] the caller must re-add
     * every polygon and, once that succeeded, call [forgetHoldingGate] with null.
     *
     * [incoming] is the polygon selection a registration pass is about to send, by id, while the
     * cache still holds the previous catalog. A wake the cache still wants is unwanted when that
     * selection drops its polygon or carries another revision: a threshold- or vertex-only change
     * leaves the circle unchanged for GMS, so the diff alone would keep the old wake armed.
     */
    suspend fun restoreStaleHoldingGate(
        onlyPolygonId: String? = null,
        incoming: Map<String, GeofenceRegion>? = null
    ): PolygonDwellWakeRestore =
        restoreHoldingGate(onlyPolygonId, keepOnlyArmedSinceFence = false, incoming = incoming)

    /**
     * With [keepOnlyArmedSinceFence], for teardown, a wanted wake survives only if this process armed
     * it since the latest fence: some teardowns keep the visit, so wanted alone cannot tell.
     */
    private suspend fun restoreHoldingGate(
        onlyPolygonId: String?,
        keepOnlyArmedSinceFence: Boolean,
        incoming: Map<String, GeofenceRegion>? = null
    ): PolygonDwellWakeRestore {
        val known = records.read() as? PolygonDwellWakeRecords.Known
            ?: return PolygonDwellWakeRestore.Unknown
        val (kept, unwanted) = known.byPolygonId.values
            .filter { onlyPolygonId == null || it.polygonId == onlyPolygonId }
            .partition { wantedNow(it) && (!keepOnlyArmedSinceFence || armedSinceFence(it)) }
        val superseded = kept.filterNot {
            incoming == null || incoming[it.polygonId]?.transitionRevision() == it.regionRevision
        }
        // A circle GMS keeps as it is gets no re-add from the diff, so its wake is undone now in place.
        // A dropped or re-shaped one is left to the pass's removal or re-add under the new definition.
        val (inPlace, leftToPass) = superseded.partition { record ->
            val next = incoming?.get(record.polygonId)
            next != null && store.getCachedRegion(record.polygonId)?.equalsForRegistration(next) == true
        }
        val token = diagnostics?.currentToken()
        val needingBaseline = (unwanted + inPlace).filterNot { restore(it, token) } + leftToPass
        return PolygonDwellWakeRestore.Known(
            needingBaseline.mapTo(mutableSetOf(), PolygonDwellWakeRecord::polygonId)
        )
    }

    /**
     * Caller holds [gate], after GMS confirmed a normal add or a removal of [polygonIds], or of every
     * geofence (null): a clearAll, or a full re-registration. Unlike a restore, each forgotten visit
     * may arm again: the confirmed operation took its wake, and a visit still pending has no other.
     */
    fun forgetHoldingGate(polygonIds: Set<String>?): Boolean {
        if (polygonIds == null) {
            if (!records.clear()) return false
            synchronized(lock) {
                attemptedVisitIds.clear()
                armedEpochs.clear()
                confirmedEpochs.clear()
            }
            return true
        }
        val token = diagnostics?.currentToken()
        val known = records.read() as? PolygonDwellWakeRecords.Known ?: return false
        return known.byPolygonId.values
            .filter { it.polygonId in polygonIds }
            .map { record ->
                val removed = records.remove(record.polygonId, record.visitId)
                if (removed) {
                    synchronized(lock) {
                        attemptedVisitIds.remove(record.visitId)
                        armedEpochs.remove(record.visitId)
                        confirmedEpochs.remove(record.visitId)
                    }
                    trace(DwellDiagnosticEvent.DEADLINE_CANCELLED, record, token)
                }
                removed
            }
            .all { it }
    }

    /**
     * Whether a registration pass has wake cleanup to run: unreadable records, or one no longer
     * wanted (see [wantedNow]): its visit ended, superseded, opted out or from another boot, or a
     * fence postdates its arm, so a failed teardown restore is retried. Synchronous, with no GMS
     * call, and never takes [gate], for a refresh deciding whether it may skip. A wanted wake needs
     * no pass.
     */
    fun needsRestorePass(): Boolean {
        val known = records.read() as? PolygonDwellWakeRecords.Known ?: return true
        return known.byPolygonId.values.any { !wantedNow(it) }
    }

    internal suspend fun arm(polygonId: String, expectedEpoch: Long): PolygonDwellWakeArm =
        gate.withRegistration { armHoldingGate(polygonId, expectedEpoch) }

    // The registrar checks the permission itself and fails without it.
    @SuppressLint("MissingPermission")
    private suspend fun armHoldingGate(polygonId: String, expectedEpoch: Long): PolygonDwellWakeArm {
        // First, so an identity clear during this pass retires it before anything is journaled.
        val token = diagnostics?.currentToken()
        if (isFenced(expectedEpoch)) return PolygonDwellWakeArm.NOT_PENDING
        val pending = pendingVisitOrNull(polygonId) ?: return PolygonDwellWakeArm.NOT_PENDING
        val nowMs = elapsedRealtimeMs()
        val delayMs = wakeDelayMsOrNull(pending, nowMs) ?: return PolygonDwellWakeArm.NOT_PENDING
        val known = records.read() as? PolygonDwellWakeRecords.Known
            ?: return PolygonDwellWakeArm.RECORDS_UNKNOWN
        val visitId = pending.visit.visitId
        if (known.byPolygonId[polygonId]?.visitId == visitId || !markAttempted(visitId)) {
            return PolygonDwellWakeArm.ALREADY_ATTEMPTED
        }
        val record = PolygonDwellWakeRecord(
            polygonId = polygonId,
            visitId = visitId,
            regionRevision = pending.visit.regionRevision,
            userStateGeneration = pending.visit.userStateGeneration,
            bootSessionId = pending.bootSessionId,
            entryElapsedMs = pending.enteredAtElapsedMs
        )
        if (!records.put(record)) return PolygonDwellWakeArm.RECORD_NOT_WRITTEN
        markArmed(visitId, expectedEpoch)
        val dueElapsedMs = nowMs + delayMs
        trace(DwellDiagnosticEvent.ARM_REQUESTED, record, token, dueElapsedMs)
        val result = registrar.armPolygonDwellWake(pending.region, delayMs)
        val timedOut = result.exceptionOrNull() is TimeoutException
        // Under the fence's lock: an answer read after a fence may have landed after the loss it marks.
        synchronized(lock) { if (epoch == expectedEpoch && !timedOut) confirmedEpochs[visitId] = expectedEpoch }
        val answered = if (result.isSuccess) DwellDiagnosticEvent.ARM_SUCCEEDED else DwellDiagnosticEvent.ARM_FAILED
        trace(answered, record, token, dueElapsedMs, DwellDiagnosticReason.GMS_TIMEOUT.takeIf { timedOut })
        // Re-checked: a teardown, identity change, new revision or visit end may have landed meanwhile.
        if (isFenced(expectedEpoch) || !stillWanted(record)) {
            restore(record, token)
            return PolygonDwellWakeArm.SUPERSEDED
        }
        // A failure or timeout leaves the OS state unknown, so the record stays for a later restore.
        if (result.isFailure) return PolygonDwellWakeArm.ARM_FAILED
        trace(DwellDiagnosticEvent.DEADLINE_SCHEDULED, record, token, dueElapsedMs)
        return PolygonDwellWakeArm.ARMED
    }

    /**
     * Holding [gate]: back to the normal ENTER|EXIT registration, then the record goes. False while
     * the registration may still carry the wake; the record is then kept for a later restore.
     */
    @SuppressLint("MissingPermission")
    private suspend fun restore(record: PolygonDwellWakeRecord, token: Long?): Boolean {
        val bootSessionId = record.bootSessionId
        if (bootSessionId != null && bootSessionId != bootSessionProvider.currentSessionId()) {
            // GMS dropped every geofence at that reboot, and boot restore re-adds them unarmed.
            return forget(record, token)
        }
        val result = if (record.polygonId !in store.getRegisteredIds()) {
            // The store says GMS holds none, but a superseded arm may have re-added it.
            trace(DwellDiagnosticEvent.DISARM_REQUESTED, record, token)
            registrar.removeGeofencesByIds(listOf(record.polygonId))
        } else {
            // With no definition the record waits until sync re-adds or removes the polygon.
            val region = store.getCachedRegion(record.polygonId)
                ?.takeIf { it.polygonGeometryOrNull() != null }
                ?: return false
            trace(DwellDiagnosticEvent.DISARM_REQUESTED, record, token)
            registrar.disarmPolygonDwellWake(region)
        }
        val timedOut = result.exceptionOrNull() is TimeoutException
        val answered =
            if (result.isSuccess) DwellDiagnosticEvent.DISARM_SUCCEEDED else DwellDiagnosticEvent.DISARM_FAILED
        trace(answered, record, token, reason = DwellDiagnosticReason.GMS_TIMEOUT.takeIf { timedOut })
        return result.isSuccess && forget(record, token)
    }

    /** A confirmed end of the wake: GMS undid it, a reboot dropped it, or a loss took an answered add. */
    private fun forget(record: PolygonDwellWakeRecord, token: Long?): Boolean {
        val removed = records.remove(record.polygonId, record.visitId)
        if (removed) {
            synchronized(lock) {
                armedEpochs.remove(record.visitId)
                confirmedEpochs.remove(record.visitId)
            }
            trace(DwellDiagnosticEvent.DEADLINE_CANCELLED, record, token)
        }
        return removed
    }

    /**
     * [stillWanted], and owned by the current epoch: no fence has run in this process, so a previous
     * process's record still counts, or this process armed it since the latest fence. One armed before
     * a fence is that fence's to restore even while its visit stays pending, so its wake is refused
     * and a failed restore is retried; only a confirmed restore, loss cleanup or baseline removes it.
     */
    private fun wantedNow(record: PolygonDwellWakeRecord): Boolean =
        stillWanted(record) && synchronized(lock) { epoch == 0L || armedEpochs[record.visitId] == epoch }

    /** The record's visit is still the pending one it was armed for, on this boot and generation. */
    private fun stillWanted(record: PolygonDwellWakeRecord): Boolean {
        val pending = pendingVisitOrNull(record.polygonId) ?: return false
        return pending.visit.visitId == record.visitId &&
            pending.visit.regionRevision == record.regionRevision &&
            pending.visit.userStateGeneration == record.userStateGeneration &&
            pending.bootSessionId == record.bootSessionId
    }

    /** A stored visit awaiting its dwell, for a registered, routable, committed-inside polygon. */
    private fun pendingVisitOrNull(polygonId: String): PendingVisit? {
        if (!isEnabled()) return null
        val userId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() } ?: return null
        if (store.activeUserSessionId() != userId ||
            polygonId !in store.getRegisteredIds() ||
            polygonId !in store.getRoutableRegisteredIds() ||
            polygonId !in store.getEnteredIds()
        ) {
            return null
        }
        val region = store.getCachedRegion(polygonId)?.takeIf {
            it.polygonGeometryOrNull() != null &&
                it.dwellThresholdSeconds in 1..GeofenceConstants.MAX_DWELL_THRESHOLD_SECONDS
        } ?: return null
        val bootSessionId = bootSessionProvider.currentSessionId()
        val visit = store.getDwellVisit(polygonId)?.takeIf {
            !it.emitted &&
                it.dwellReservation == null &&
                it.regionRevision == region.transitionRevision() &&
                it.userStateGeneration == store.userStateGeneration() &&
                it.bootSessionId == bootSessionId
        } ?: return null
        val enteredAtElapsedMs = visit.enteredAtElapsedMs ?: return null
        return PendingVisit(region, visit, bootSessionId, enteredAtElapsedMs)
    }

    /**
     * Entry plus threshold plus grace, all from the original entry. Null for an entry stamped in the
     * future, which this clock cannot place, or a visit already due, whose evaluation already asks
     * for proof.
     */
    private fun wakeDelayMsOrNull(pending: PendingVisit, nowMs: Long): Long? {
        if (pending.enteredAtElapsedMs > nowMs) return null
        val untilDueMs = pending.region.dwellThresholdSeconds * MILLIS_PER_SECOND -
            (nowMs - pending.enteredAtElapsedMs)
        if (untilDueMs <= 0L) return null
        return (untilDueMs + graceMs).coerceAtMost(Int.MAX_VALUE.toLong())
    }

    /**
     * Supersedes every arm that captured an earlier epoch, and returns the new one. No I/O or
     * logging, so the controller runs it under the lock its teardown or new session changes state
     * under, then hands the result to [onTeardown] or [onOsRegistrationsLost] outside that lock.
     */
    fun fence(): Long = synchronized(lock) {
        epoch += 1
        // Every earlier wake is undone by the fence's restore, or went with GMS's registrations, so
        // a visit still pending may arm once more.
        attemptedVisitIds.clear()
        epoch
    }

    /**
     * Tags [visitId]'s write-ahead record with the epoch its arm began in, for the teardown restore
     * and, through [confirmedEpochs], the OS-loss cleanup. Pruned only to recorded visits: an
     * untagged record is restored at teardown and kept by the loss cleanup, so a lost tag orphans none. Called
     * holding [gate], so the records cannot change between the read and the prune.
     */
    private fun markArmed(visitId: String, armEpoch: Long) {
        if (synchronized(lock) { armedEpochs.size } >= MAX_ATTEMPTED_VISITS) {
            (records.read() as? PolygonDwellWakeRecords.Known)?.byPolygonId?.values
                ?.mapTo(mutableSetOf(), PolygonDwellWakeRecord::visitId)
                ?.let { recorded ->
                    synchronized(lock) {
                        armedEpochs.keys.retainAll(recorded)
                        confirmedEpochs.keys.retainAll(recorded)
                    }
                }
        }
        synchronized(lock) { armedEpochs[visitId] = armEpoch }
    }

    /** An untagged record, one another process armed on this boot included, is never assumed gone. */
    private fun answeredBefore(record: PolygonDwellWakeRecord, fenceEpoch: Long): Boolean =
        synchronized(lock) { confirmedEpochs[record.visitId]?.let { it < fenceEpoch } == true }

    private fun armedSinceFence(record: PolygonDwellWakeRecord): Boolean =
        synchronized(lock) { armedEpochs[record.visitId] == epoch }

    private fun isFenced(expectedEpoch: Long): Boolean = synchronized(lock) { epoch != expectedEpoch }

    /** False when this process already tried [visitId]. Bounded: ids are random per visit. */
    private fun markAttempted(visitId: String): Boolean = synchronized(lock) {
        if (attemptedVisitIds.size >= MAX_ATTEMPTED_VISITS) attemptedVisitIds.clear()
        attemptedVisitIds.add(visitId)
    }

    /**
     * Never alters a decision. Drops what a cleared journal must not see: a [token] retired by a
     * clear, or a visit from another user session.
     */
    private fun trace(
        event: DwellDiagnosticEvent,
        record: PolygonDwellWakeRecord,
        token: Long?,
        dueElapsedMs: Long? = null,
        reason: DwellDiagnosticReason? = null
    ) {
        val recorder = diagnostics ?: return
        if (token == null) return
        runCatching {
            if (record.userStateGeneration != store.userStateGeneration()) return@runCatching
            val entry = DwellDiagnosticRecord(
                event = event,
                atElapsedMs = elapsedRealtimeMs(),
                fenceId = record.polygonId,
                dueElapsedMs = dueElapsedMs,
                reason = reason
            )
            recorder.record(entry, token)
        }
    }

    private fun isEnabled(): Boolean = runCatching { enabled() }.getOrDefault(false)

    companion object {
        /** GMS's loitering clock is not ours: a wake landing early would find the visit not yet due. */
        const val DEFAULT_GRACE_MS = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MAX_ATTEMPTED_VISITS = 256
    }
}
