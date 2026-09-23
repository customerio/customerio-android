package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonUndecidedReason
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Producer-side contract for the geofence diagnostics tail.
 *
 * The tail is an untyped string contract consumed by a parser that lives off-device and in another
 * language, so there is no round trip to assert. What can be asserted here is the half we own: that
 * every geofence logger method emits a machine key and a replay classification, that no value can
 * break the parser's whitespace split, and — the load-bearing one — that with the gate off the
 * output is exactly what it was before any of this instrumentation existed.
 *
 * It also pins the derived `why=` tokens. Those are computed from the existing prose rather than
 * from an enum — which is what keeps `logSyncSkipped`'s 20 test references untouched — and the
 * price of that choice is that a reworded sentence would silently change what analysis groups on.
 * Pinning them here converts that into a failing test.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceLogTailTest : RobolectricTest() {

    /** Captures formatted messages. What these tests need is the exact string, not a call count. */
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()
        override var logLevel: CioLogLevel = CioLogLevel.DEBUG

        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit

        override fun info(message: String, tag: String?) = record(message, tag)
        override fun debug(message: String, tag: String?) = record(message, tag)
        override fun error(message: String, tag: String?, throwable: Throwable?) = record(message, tag)

        private fun record(message: String, tag: String?) {
            messages.add(if (tag != null) "[$tag] $message" else message)
        }
    }

    private lateinit var capturing: CapturingLogger
    private lateinit var geofenceLogger: GeofenceLogger

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
        capturing = CapturingLogger()
        geofenceLogger = GeofenceLogger(capturing)
        GeofenceDiagnostics.setEnabledForTesting(true)
    }

    @After
    fun resetDiagnostics() {
        // null, not false: null restores the manifest value, false pins the gate off for every
        // later test class in this JVM.
        GeofenceDiagnostics.setEnabledForTesting(null)
    }

    /**
     * `ok` on `permission.changed` means background delivery is available — the reading iOS uses,
     * where the flag is written only when Always is granted and WhenInUse carries
     * `why=foreground_only` instead.
     *
     * The row-level check below only asserts that `perm` and `ok` are present, and only for Always,
     * so the values were free to drift. They had: `ok` used to mean "some permission was granted",
     * which made WhenInUse — the one tier where geofences fire in the foreground and nowhere else —
     * report `ok=true` here and no `ok` at all on iOS.
     */
    @Test
    fun logPermissionTier_givenEachTier_expectOkToMeanBackgroundDelivery() {
        val cases = mapOf(
            GeofenceLogger.PERMISSION_ALWAYS to "true",
            GeofenceLogger.PERMISSION_WHEN_IN_USE to "false",
            GeofenceLogger.PERMISSION_DENIED to "false"
        )
        for ((tier, expectedOk) in cases) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logPermissionTier(tier)
            val fields = parseTail(logger.messages.last())
            fields.shouldNotBeNull()
            fields["perm"] shouldBeEqualTo tier
            fields["ok"] shouldBeEqualTo expectedOk
        }
    }

    /**
     * `perm` carries the tier vocabulary on every writer of this record, including the refusal
     * path, which used to put an Android permission constant there instead.
     */
    @Test
    fun logMissingPermission_expectTheTierInPermAndTheConstantInCtx() {
        val logger = CapturingLogger()
        GeofenceLogger(logger).logMissingPermission("ACCESS_FINE_LOCATION")

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["perm"] shouldBeEqualTo GeofenceLogger.PERMISSION_DENIED
        // Normalised by `token()`, as every other `ctx` on this record is.
        fields["ctx"] shouldBeEqualTo "access_fine_location"
    }

    /**
     * Mirrors what the off-device parser does: split on the **last** delimiter, then accept the
     * remainder only if every token is a `key=value` pair.
     */
    private fun parseTail(message: String): Map<String, String>? {
        val index = message.lastIndexOf(GeofenceLogTail.DELIMITER)
        if (index < 0) return null
        val tail = message.substring(index + GeofenceLogTail.DELIMITER.length)
        val fields = mutableMapOf<String, String>()
        for (token in tail.split(" ")) {
            val parts = token.split("=", limit = 2)
            if (parts.size != 2) return null
            fields[parts[0]] = parts[1]
        }
        return fields.ifEmpty { null }
    }

    private fun location(
        accuracy: Float = 48f,
        speed: Float = 18.3f,
        bearing: Float = 91f,
        mock: Boolean = false
    ): Location = Location("test").apply {
        latitude = 43.2557
        longitude = -79.0713
        altitude = 95.0
        this.accuracy = accuracy
        this.speed = speed
        this.bearing = bearing
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        if (mock) isMock = true
    }

    /**
     * One entry per logger method, paired with the keys its record must carry.
     *
     * Enumerated by hand because there is no reflection over an extension of behaviour like this. A
     * new method added without a line here is simply uncovered — but a *renamed key* on anything
     * listed here fails loudly, which is the failure this contract exists to catch.
     */
    /**
     * One row per record. [ev] is pinned per row, not just collected into a set: a set is
     * identical whether two records keep their keys or swap them, and a swap silently inverts
     * what every capture says the SDK decided.
     */
    private data class Row(
        val name: String,
        val ev: String,
        val requiredKeys: List<String>,
        /** The method this row exercises, by reference so a rename cannot leave it behind. */
        val method: String,
        /** Pinned per row: `io` decides what replay feeds back and what it compares. */
        val io: String = "out",
        /**
         * Exact values a consumer discriminates on, where presence alone is not enough. Two records
         * sharing an `ev` and differing only by `why` pass every other check if their reasons are
         * swapped, so the reason has to be pinned or the pair is untested.
         */
        val pinned: Map<String, String> = emptyMap(),
        val run: (GeofenceLogger) -> Unit
    )

    /**
     * The frozen replay contract: `in` is what replay injects, `out` is the entire assertion
     * surface, everything else is `obs`. Pinned per `ev`, which is what a parser dispatches on.
     */
    private val declaredIo: Map<String, String> = mapOf(
        "api.fetch.result" to "in",
        "fence.cataloged" to "in",
        "identity.changed" to "in",
        "location.fix" to "in",
        "module.init" to "in",
        "module.wake" to "in",
        "os.callback.received" to "in",
        "os.error" to "in",
        "permission.changed" to "in",
        "module.reset" to "out",
        "os.callback.dropped" to "obs",
        "registration.applied" to "out",
        "transition.accepted" to "out",
        "transition.dropped" to "obs",
        "transition.suppressed" to "obs",
        "transition.synthesized" to "obs"
    )

    /** Named separately from [declaredIo] so the count is visible at a glance. */
    private val frozenOutputs = setOf(
        "module.reset",
        "registration.applied",
        "transition.accepted"
    )

    private fun invocations(): List<Row> {
        val fix = location()
        return listOf(
            Row("geofencesRegistered", "registration.added", listOf("nadd"), GeofenceLogger::logGeofencesRegistered.name, io = "obs") { it.logGeofencesRegistered(19) },
            Row("regionsRegisteredIds", "registration.applied", listOf("n", "ids", "mvmt"), GeofenceLogger::logRegionsRegisteredIds.name) { it.logRegionsRegisteredIds(listOf("a", "b"), "cio_movement_trigger") },
            Row("businessKept", "registration.kept", listOf("nkeep", "why"), GeofenceLogger::logBusinessGeofencesKept.name, io = "obs") { it.logBusinessGeofencesKept(4) },
            Row("geofencesRemoved", "registration.removed", listOf("nrem"), GeofenceLogger::logGeofencesRemoved.name, io = "obs") { it.logGeofencesRemoved(2) },
            Row("geofencesCleared", "registration.cleared", listOf("why"), GeofenceLogger::logGeofencesCleared.name, io = "obs") { it.logGeofencesCleared() },
            Row("registrationFailed", "registration.failed", listOf("ok", "why"), GeofenceLogger::logRegistrationFailed.name, io = "obs") { it.logRegistrationFailed("GMS unavailable") },
            Row("removalFailed", "registration.failed", listOf("ok", "op", "why"), GeofenceLogger::logRemovalFailed.name, io = "obs") { it.logRemovalFailed("GMS unavailable") },
            Row("invalidRegionDropped", "registration.rejected", listOf("id", "why"), GeofenceLogger::logInvalidRegionDropped.name, io = "obs") { it.logInvalidRegionDropped("notl core") },
            Row("regionMappingFailed", "registration.rejected", listOf("id", "why"), GeofenceLogger::logRegionMappingFailed.name, io = "obs") { it.logRegionMappingFailed("notl_core", "bad radius") },
            Row("rankEvaluated", "rank.evaluated", listOf("ncand", "n", "ranked", "evicted"), GeofenceLogger::logRankEvaluated.name, io = "obs") { it.logRankEvaluated(30, 2, { listOf("a", "b") }, { listOf("c") }, { mapOf("a" to 120.0, "b" to 340.0) }) },
            Row("movementTriggerRegistered", "movement.registered", listOf("rad"), GeofenceLogger::logMovementTriggerRegistered.name, io = "obs") { it.logMovementTriggerRegistered(43.2, -79.0, 500.0) },
            Row("missingPermission", "permission.changed", listOf("perm", "why"), GeofenceLogger::logMissingPermission.name, io = "in") { it.logMissingPermission("ACCESS_FINE_LOCATION") },
            Row("backgroundUnavailable", "permission.changed", listOf("perm", "ctx"), GeofenceLogger::logBackgroundDeliveryUnavailable.name, io = "in") { it.logBackgroundDeliveryUnavailable("app-launch") },
            Row("moduleInitialized", "module.init", listOf("launch"), GeofenceLogger::logModuleInitialized.name, io = "in") { it.logModuleInitialized(GeofenceLaunchReason.APP_START) },
            Row("moduleWoke", "module.wake", listOf("launch"), GeofenceLogger::logModuleWoke.name, io = "in") { it.logModuleWoke(GeofenceLaunchReason.BOOT_RESTORE) },
            Row("missingLocationModule", "module.init", listOf("ok", "why"), GeofenceLogger::logMissingLocationModule.name, io = "in") { it.logMissingLocationModule() },
            Row("stateResetOnSignOut", "info", listOf("why"), GeofenceLogger::logGeofenceStateResetOnSignOut.name, io = "obs") { it.logGeofenceStateResetOnSignOut() },
            Row("resetCompleted", "module.reset", listOf("ok"), GeofenceLogger::logResetCompleted.name) { it.logResetCompleted() },
            Row("resetSuperseded", "module.reset", listOf("ok", "why"), GeofenceLogger::logResetSuperseded.name) { it.logResetSuperseded() },
            Row("resetFailed", "module.reset", listOf("ok", "why"), GeofenceLogger::logResetFailed.name) { it.logResetFailed("ApiException") },
            Row("identityChanged", "identity.changed", listOf("ok"), GeofenceLogger::logIdentityChanged.name, io = "in") { it.logIdentityChanged(true) },
            Row("locationFix", "location.fix", listOf("lat", "lon", "prov"), GeofenceLogger::logLocationFix.name, io = "in") { it.logLocationFix(43.2, -79.0) },
            Row("permissionTier", "permission.changed", listOf("perm", "ok"), GeofenceLogger::logPermissionTier.name, io = "in") { it.logPermissionTier(GeofenceLogger.PERMISSION_ALWAYS) },
            Row("dispatchReady", "dispatch.ready", listOf("ms"), GeofenceLogger::logDispatchReady.name, io = "obs") { it.logDispatchReady(12L) },
            Row("callbackReceived", "os.callback.received", listOf("ids", "n", "t", "fixsrc", "acc", "age", "sim"), GeofenceLogger::logCallbackReceived.name, io = "in") { it.logCallbackReceived(listOf("notl_core"), "ENTER", fix, GeofenceLogTail.FixSource.OS_TRIGGER) },
            Row("callbackReceivedNoFix", "os.callback.received", listOf("fixsrc"), GeofenceLogger::logCallbackReceived.name, io = "in") { it.logCallbackReceived(listOf("notl_core"), "EXIT", null, GeofenceLogTail.FixSource.NONE) },
            Row("transitionWithoutLocation", "os.callback.no_location", listOf("fixsrc", "why"), GeofenceLogger::logTransitionWithoutLocation.name, io = "obs") { it.logTransitionWithoutLocation() },
            Row("unknownTransition", "os.callback.dropped", listOf("id", "gms", "why"), GeofenceLogger::logUnknownTransition.name, io = "obs") { it.logUnknownTransition("notl_core", 4) },
            Row("info", "info", listOf("why"), GeofenceLogger::logInfo.name, io = "obs") { it.logInfo("broadcast_no_triggering_geofences") },
            Row("movementIgnoredNonExit", "os.callback.dropped", listOf("t", "why"), GeofenceLogger::logMovementTriggerIgnoredNonExit.name, io = "obs") { it.logMovementTriggerIgnoredNonExit("ENTER") },
            Row("receiverSkipped", "os.callback.dropped", listOf("why"), GeofenceLogger::logReceiverSkipped.name, io = "obs") { it.logReceiverSkipped("no identified user") },
            Row("geofencingError", "os.error", listOf("ok", "code"), GeofenceLogger::logGeofencingError.name, io = "in") { it.logGeofencingError(1000) },
            Row("transitionAccepted", "transition.accepted", listOf("id", "t", "n"), GeofenceLogger::logTransitionAccepted.name) { it.logTransitionAccepted("notl_core", "ENTER", 2) },
            Row("transitionSuppressed", "transition.suppressed", listOf("id", "t", "why", "cd"), GeofenceLogger::logTransitionSuppressed.name, io = "obs") { it.logTransitionSuppressed("notl_core", "ENTER", 42.0) },
            Row("initialEnterInside", "transition.synthesized", listOf("id", "t", "why"), GeofenceLogger::logInitialEnterInside.name, io = "obs") { it.logInitialEnterInside("notl_core") },
            Row("droppedUnknownId", "transition.dropped", listOf("id", "why"), GeofenceLogger::logTransitionDroppedUnknownId.name, io = "obs") { it.logTransitionDroppedUnknownId("notl_core") },
            Row("droppedRetiredId", "transition.dropped", listOf("id", "why"), GeofenceLogger::logTransitionDroppedRetiredId.name, pinned = mapOf("why" to "retired_id"), io = "obs") { it.logTransitionDroppedRetiredId("notl_core") },
            Row("droppedUnarmedId", "transition.dropped", listOf("id", "why"), GeofenceLogger::logTransitionDroppedUnarmedId.name, pinned = mapOf("why" to "routing_unarmed"), io = "obs") { it.logTransitionDroppedUnarmedId("notl_core") },
            Row("containmentJudged", "containment.judged", listOf("n"), GeofenceLogger::logContainmentJudged.name, io = "obs") { it.logContainmentJudged(3) },
            Row("gmsCallTimedOut", "os.error", listOf("ok", "op", "why"), GeofenceLogger::logGmsCallTimedOut.name, io = "in", pinned = mapOf("why" to "timeout", "ok" to "false")) { it.logGmsCallTimedOut("addGeofences") },
            Row("eventDeliveredNotRemoved", "delivery.sent", listOf("id", "t", "ok", "retry", "why"), GeofenceLogger::logEventDeliveredButNotRemoved.name, pinned = mapOf("why" to "not_removed", "ok" to "true", "retry" to "true"), io = "obs") { it.logEventDeliveredButNotRemoved("notl_core", "ENTER") },
            Row("enterDroppedAlreadyReported", "transition.dropped", listOf("id", "t", "why"), GeofenceLogger::logEnterDroppedAlreadyReported.name, io = "obs") { it.logEnterDroppedAlreadyReported("notl_core") },
            Row("exitDroppedNeverEntered", "transition.dropped", listOf("id", "t", "why"), GeofenceLogger::logExitDroppedNeverEntered.name, io = "obs") { it.logExitDroppedNeverEntered("notl_core") },
            Row("droppedAnonymous", "transition.dropped", listOf("id", "t", "why"), GeofenceLogger::logTransitionDroppedAnonymous.name, io = "obs") { it.logTransitionDroppedAnonymous("notl_core", "EXIT") },
            Row("syncTriggered", "sync.triggered", listOf("why"), GeofenceLogger::logSyncTriggered.name, io = "obs") { it.logSyncTriggered("app-launch") },
            Row("syncSkipped", "sync.skipped", listOf("why"), GeofenceLogger::logSyncSkipped.name, io = "obs") { it.logSyncSkipped("no identified user") },
            Row("syncSkippedNoLocation", "sync.skipped", listOf("why", "ctx"), GeofenceLogger::logSyncSkippedNoLocation.name, io = "obs") { it.logSyncSkippedNoLocation("app-launch") },
            Row("syncSkippedInvalidLocation", "sync.skipped", listOf("why", "ctx"), GeofenceLogger::logSyncSkippedInvalidLocation.name, io = "obs") { it.logSyncSkippedInvalidLocation("app-launch", 0.0, 0.0) },
            Row("syncSkippedNoPermission", "sync.skipped", listOf("why", "ctx"), GeofenceLogger::logSyncSkippedNoPermission.name, io = "obs") { it.logSyncSkippedNoPermission("boot-restore") },
            Row("syncSkippedFresh", "sync.skipped", listOf("why"), GeofenceLogger::logSyncSkippedFresh.name, io = "obs") { it.logSyncSkippedFresh() },
            Row("syncFailed", "sync.failed", listOf("ok", "why"), GeofenceLogger::logSyncFailed.name, io = "obs") { it.logSyncFailed("timeout") },
            Row("syncSucceeded", "sync.completed", listOf("n", "mvmt"), GeofenceLogger::logSyncSucceeded.name, io = "obs") { it.logSyncSucceeded(19, true) },
            Row("apiFetchResult", "api.fetch.result", listOf("ok", "n", "ms"), GeofenceLogger::logApiFetchResult.name, io = "in") { it.logApiFetchResult(30, 420L) },
            Row("apiFetchFailed", "api.fetch.result", listOf("ok", "why"), GeofenceLogger::logApiFetchFailed.name, io = "in") { it.logApiFetchFailed("timeout") },

            Row("unknownApiTransitionType", "api.transition.unknown", listOf("ok", "why", "value"), GeofenceLogger::logUnknownApiTransitionType.name, io = "obs") { it.logUnknownApiTransitionType("dwell") },
            Row("movementRearmed", "movement.rearmed", listOf("why"), GeofenceLogger::logMovementRearmedAfterFailedRefresh.name, io = "obs") { it.logMovementRearmedAfterFailedRefresh() },
            Row("storageLoaded", "storage.loaded", listOf("n", "anchor"), GeofenceLogger::logStorageLoaded.name, io = "obs") { it.logStorageLoaded({ 30 }, true) },
            Row("persistFailed", "storage.write.failed", listOf("id", "t", "ok"), GeofenceLogger::logPersistFailed.name, io = "obs") { it.logPersistFailed("notl_core", "ENTER") },
            Row("deliveryRetryable", "delivery.failed", listOf("id", "t", "ok", "retry", "why"), GeofenceLogger::logEventDeliveryRetryable.name, io = "obs") { it.logEventDeliveryRetryable("notl_core", "ENTER", "socket timeout") },
            Row("deliveryFailed", "delivery.failed", listOf("id", "t", "ok", "retry", "why"), GeofenceLogger::logEventDeliveryFailed.name, io = "obs") { it.logEventDeliveryFailed("notl_core", "ENTER", "400 bad request", willRetry = false) },
            Row("eventInvalidInput", "delivery.failed", listOf("ok", "why"), GeofenceLogger::logEventInvalidInput.name, io = "obs") { it.logEventInvalidInput(null, null) },
            Row("deliveryDeferredAnonymous", "delivery.queued", listOf("id", "t", "why"), GeofenceLogger::logEventDeliveryDeferredAnonymous.name, io = "obs") { it.logEventDeliveryDeferredAnonymous("notl_core", "ENTER") },
            Row("eventDelivered", "delivery.sent", listOf("id", "t", "via"), GeofenceLogger::logEventDelivered.name, io = "obs") { it.logEventDelivered("notl_core", "ENTER") },
            Row("deliverySkippedAlreadyDelivered", "delivery.sent", listOf("id", "t", "why"), GeofenceLogger::logEventDeliverySkippedAlreadyDelivered.name, io = "obs") { it.logEventDeliverySkippedAlreadyDelivered("notl_core", "ENTER") },
            Row("workerEntryMissing", "delivery.sent", listOf("why"), GeofenceLogger::logEventWorkerEntryMissing.name, io = "obs") { it.logEventWorkerEntryMissing() },
            Row("workerQueueUnreadable", "queue.unreadable", listOf("why", "via", "n", "retry"), GeofenceLogger::logEventWorkerQueueUnreadable.name, io = "in") { it.logEventWorkerQueueUnreadable(2, willRetry = true) },
            Row("flushQueueUnreadable", "queue.unreadable", listOf("why", "via"), GeofenceLogger::logForegroundFlushQueueUnreadable.name, io = "in") { it.logForegroundFlushQueueUnreadable() },
            Row("flushSnapshot", "delivery.flush", listOf("n", "phase"), GeofenceLogger::logForegroundFlushSnapshot.name, io = "obs") { it.logForegroundFlushSnapshot(3) },
            Row("flushCancelled", "delivery.flush", listOf("id", "t", "why"), GeofenceLogger::logForegroundFlushCancelledWorkManager.name, io = "obs") { it.logForegroundFlushCancelledWorkManager("notl_core", "ENTER") },
            Row("flushPublished", "delivery.sent", listOf("id", "t", "via"), GeofenceLogger::logForegroundFlushPublished.name, io = "obs") { it.logForegroundFlushPublished("notl_core", "ENTER") },
            Row("flushEntryFailed", "delivery.failed", listOf("id", "t", "ok", "via", "why"), GeofenceLogger::logForegroundFlushEntryFailed.name, io = "obs") { it.logForegroundFlushEntryFailed("notl_core", "ENTER", "boom") },
            Row("flushComplete", "delivery.flush", listOf("n", "phase", "ok"), GeofenceLogger::logForegroundFlushComplete.name, io = "obs") { it.logForegroundFlushComplete(3) },
            Row("asyncDeliveryFailed", "delivery.failed", listOf("id", "t", "ok", "why"), GeofenceLogger::logAsyncDeliveryFailed.name, io = "obs") { it.logAsyncDeliveryFailed("notl_core", "ENTER", "boom") },
            Row("schedulerFailed", "delivery.failed", listOf("id", "t", "ok", "why", "detail"), GeofenceLogger::logSchedulerFailed.name, io = "obs") { it.logSchedulerFailed("notl_core", "ENTER", "boom") },

            Row("polygonDropped", "registration.rejected", listOf("id", "sh", "why"), GeofenceLogger::logPolygonDropped.name, io = "obs") { it.logPolygonDropped("notl_core", PolygonDropReason.RING_UNBUILDABLE) },
            Row("polygonDroppedUnsupportedRuntime", "registration.rejected", listOf("id", "sh", "why"), GeofenceLogger::logPolygonDroppedUnsupportedRuntime.name, io = "obs") { it.logPolygonDroppedUnsupportedRuntime("notl_core") },
            Row("unsupportedGeometryDropped", "registration.rejected", listOf("id", "sh", "why"), GeofenceLogger::logUnsupportedGeometryDropped.name, io = "obs") { it.logUnsupportedGeometryDropped("notl_core", "LineString") },
            Row("polygonRegionNotRanked", "rank.excluded", listOf("id", "sh", "why"), GeofenceLogger::logPolygonRegionNotRanked.name, io = "obs") { it.logPolygonRegionNotRanked("notl_core", PolygonNotRankedReason.RING_UNBUILDABLE) },
            Row("polygonApproachRequestDiscarded", "polygon.approach.request_discarded", listOf("gen"), GeofenceLogger::logPolygonApproachRequestDiscarded.name, io = "obs") { it.logPolygonApproachRequestDiscarded(4L) },
            Row("polygonApproachStopRefused", "polygon.approach.stop_refused", listOf("why"), GeofenceLogger::logPolygonApproachStopRefused.name, pinned = mapOf("why" to "not_current_session"), io = "obs") { it.logPolygonApproachStopRefused(PolygonApproachStopRefusal.NOT_CURRENT_SESSION) },
            Row("polygonSamplingSkipped", "polygon.sampling.skipped", listOf("why"), GeofenceLogger::logPolygonSamplingSkipped.name, pinned = mapOf("why" to "not_current_session"), io = "obs") { it.logPolygonSamplingSkipped(PolygonSamplingSkip.NOT_CURRENT_SESSION) },
            Row("polygonEvaluationSkipped", "polygon.evaluation.skipped", listOf("why"), GeofenceLogger::logPolygonEvaluationSkipped.name, pinned = mapOf("why" to "outbox_blocked"), io = "obs") { it.logPolygonEvaluationSkipped(PolygonEvaluationSkip.OUTBOX_BLOCKED) },
            Row("polygonArrivalExpired", "polygon.arrival.expired", listOf("id", "sh", "why", "held"), GeofenceLogger::logPolygonArrivalExpired.name, pinned = mapOf("why" to "window_elapsed"), io = "obs") { it.logPolygonArrivalExpired("notl_core", PolygonArrivalExpiry.WINDOW_ELAPSED, 61.0) },
            Row("polygonArrivalPending", "polygon.arrival.pending", listOf("id", "sh", "edge", "acc", "age"), GeofenceLogger::logPolygonArrivalPending.name, io = "obs") { it.logPolygonArrivalPending("notl_core", 10.0, 18.0, 0.3) },
            Row("polygonCallbackDropped", "os.callback.dropped", listOf("why", "id"), GeofenceLogger::logPolygonCallbackDropped.name, io = "obs", pinned = mapOf("why" to "not_routable")) { it.logPolygonCallbackDropped(PolygonCallbackDrop.NOT_ROUTABLE, "notl_core") },
            Row("polygonFixNotUsable", "polygon.undecided", listOf("why", "acc", "age"), GeofenceLogger::logPolygonFixNotUsable.name, pinned = mapOf("why" to "fix_too_old"), io = "obs") { it.logPolygonFixNotUsable(PolygonFixRejection.FIX_TOO_OLD, horizontalAccuracyMeters = 28.0, fixAgeSeconds = 91.0) },
            Row("polygonUnchanged", "polygon.unchanged", listOf("id", "sh", "m", "edge", "acc", "age"), GeofenceLogger::logPolygonUnchanged.name, pinned = mapOf("m" to "inside"), io = "obs") { it.logPolygonUnchanged("notl_core", "INSIDE", 22.0, 9.0, 1.0) },
            Row("polygonDecided", "polygon.decided", listOf("id", "sh", "t", "edge", "acc", "age", "cor"), GeofenceLogger::logPolygonDecided.name, pinned = mapOf("t" to "enter", "cor" to "true"), io = "obs") { it.logPolygonDecided("notl_core", "enter", 6.2, 18.0, 3.0, corroborated = true) },
            Row("polygonUndecided", "polygon.undecided", listOf("id", "sh", "why", "edge", "acc", "age"), GeofenceLogger::logPolygonUndecided.name, pinned = mapOf("why" to "within_accuracy"), io = "obs") { it.logPolygonUndecided("notl_core", PolygonUndecidedReason.WITHIN_ACCURACY, -12.5, 30.0, 4.0) },
            Row("pinnedRegionDroppedAtOsLimit", "registration.rejected", listOf("id", "n", "why"), GeofenceLogger::logPinnedRegionDroppedAtOsLimit.name, io = "obs") { it.logPinnedRegionDroppedAtOsLimit("notl_core", 19) },
            Row("polygonApproachStarted", "polygon.approach.started", emptyList(), GeofenceLogger::logPolygonApproachMonitoringStarted.name, io = "obs") { it.logPolygonApproachMonitoringStarted() },
            Row("polygonApproachStopped", "polygon.approach.stopped", listOf("n"), GeofenceLogger::logPolygonApproachMonitoringStopped.name, io = "obs") { it.logPolygonApproachMonitoringStopped(samplesReceived = 0) },
            Row("polygonApproachRequestFailed", "polygon.approach.failed", listOf("ok", "op", "why"), GeofenceLogger::logPolygonApproachRequestFailed.name, io = "in") { it.logPolygonApproachRequestFailed("no permission", operation = "request_updates") },
            Row("polygonApproachProcessingFailed", "polygon.approach.dropped", listOf("ok", "op", "why"), GeofenceLogger::logPolygonApproachProcessingFailed.name, io = "obs") { it.logPolygonApproachProcessingFailed("boom", operation = "deliver") },
            Row("polygonFreshFixRequested", "polygon.freshfix.requested", listOf("ids", "n"), GeofenceLogger::logPolygonFreshFixRequested.name, io = "obs") { it.logPolygonFreshFixRequested(listOf("notl_core")) },
            Row("polygonFreshFixReceived", "polygon.freshfix.received", listOf("waited", "fixsrc", "acc", "age"), GeofenceLogger::logPolygonFreshFixReceived.name, io = "in") { it.logPolygonFreshFixReceived(fix, waitedSeconds = 1.4) },
            Row("polygonFreshFixSkipped", "polygon.freshfix.skipped", listOf("why"), GeofenceLogger::logPolygonFreshFixSkipped.name, pinned = mapOf("why" to "none_arrived"), io = "obs") { it.logPolygonFreshFixSkipped(PolygonFreshFixSkip.NONE_ARRIVED) },
            Row("polygonRecheckRan", "polygon.recheck.ran", listOf("cand", "n", "ids", "ncleared", "cleared", "fixsrc", "acc", "age"), GeofenceLogger::logPolygonRecheckRan.name, io = "in") { it.logPolygonRecheckRan(fix, candidateCount = 3, admittedIds = listOf("notl_core"), clearedIds = listOf("notl_annex")) },
            Row("polygonRecheckSkipped", "polygon.recheck.skipped", listOf("why"), GeofenceLogger::logPolygonRecheckSkipped.name, pinned = mapOf("why" to "nothing_registered"), io = "obs") { it.logPolygonRecheckSkipped(PolygonRecheckSkip.NOTHING_REGISTERED) },
            Row("polygonPassiveStarted", "polygon.passive.started", emptyList(), GeofenceLogger::logPolygonPassiveStarted.name, io = "obs") { it.logPolygonPassiveStarted() },
            Row("polygonPassiveStopped", "polygon.passive.stopped", emptyList(), GeofenceLogger::logPolygonPassiveStopped.name, io = "obs") { it.logPolygonPassiveStopped() },
            Row("polygonPassiveReceived", "polygon.passive.received", listOf("cand", "n", "ids", "ncleared", "cleared", "fixsrc", "acc", "age"), GeofenceLogger::logPolygonPassiveReceived.name, io = "in") { it.logPolygonPassiveReceived(fix, candidateCount = 2, admittedIds = listOf("notl_core"), clearedIds = listOf("notl_annex")) },
            Row("polygonPassiveSkipped", "polygon.passive.skipped", listOf("why"), GeofenceLogger::logPolygonPassiveSkipped.name, pinned = mapOf("why" to "nothing_registered"), io = "obs") { it.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED) },
            Row("polygonPassiveFailed", "polygon.passive.failed", listOf("why"), GeofenceLogger::logPolygonPassiveFailed.name, pinned = mapOf("why" to "boom"), io = "obs") { it.logPolygonPassiveFailed("boom") }
        )
    }

    // MARK: - Contract

    @Test
    fun everyRecord_givenAnyMethod_expectMachineKeyAndReplayClassification() {
        for (row in invocations()) {
            val (name, ev, requiredKeys) = row
            val run = row.run
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))

            // Last message, not first: the precise-location warning would precede a gated record.
            val message = logger.messages.lastOrNull()
            message.shouldNotBeNull()
            val fields = parseTail(message)
            fields.shouldNotBeNull()

            if (fields["ev"] != ev) {
                throw AssertionError("$name: expected ev=$ev, got '${fields["ev"]}'")
            }
            // Pinned, not merely one of the three: `io` is what the off-device transform routes on,
            // so a record flipped from out to in is replayed as something the SDK was told.
            if (fields["io"] != row.io) {
                throw AssertionError("$name: expected io=${row.io}, got '${fields["io"]}'")
            }
            for ((key, value) in row.pinned) {
                if (fields[key] != value) {
                    throw AssertionError("$name: expected $key=$value, got '${fields[key]}'")
                }
            }
            for (key in requiredKeys) {
                if (fields[key] == null) {
                    throw AssertionError("$name: missing $key= in '$message'")
                }
            }
        }
    }

    /** Reads what the logger emitted, not [declaredIo], so a mistake in the map cannot validate itself. */
    @Test
    fun assertionSurface_expectExactlyTheFrozenOutputs() {
        val emitted = sortedSetOf<String>()
        for (row in invocations()) {
            val logger = CapturingLogger()
            row.run(GeofenceLogger(logger))
            val fields = logger.messages.lastOrNull()?.let { parseTail(it) } ?: continue
            if (fields["io"] == "out") emitted.add(fields["ev"] ?: row.ev)
        }
        emitted shouldBeEqualTo frozenOutputs.toSortedSet()
    }

    @Test
    fun everyRecord_givenAnyMethod_expectProseAndTailSeparated() {
        for (row in invocations()) {
            val name = row.name
            val run = row.run
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))
            // Diagnostics-only entry points emit nothing at all with the gate off, which is a
            // stronger guarantee than emitting unchanged prose.
            val message = logger.messages.lastOrNull() ?: continue

            // Prose in front, machine-readable behind. A record that is all tail has lost the
            // human-readable half Logcat still depends on.
            val head = message.substringBefore(GeofenceLogTail.DELIMITER)
            if (!head.startsWith("[Geofence] ") || head.length <= "[Geofence] ".length || head.contains("ev=")) {
                throw AssertionError("$name: prose half is wrong — '$message'")
            }
        }
    }

    @Test
    fun everyValue_givenAnyMethod_expectNoWhitespace() {
        for (row in invocations()) {
            val name = row.name
            val run = row.run
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))
            // Diagnostics-only entry points emit nothing at all with the gate off, which is a
            // stronger guarantee than emitting unchanged prose.
            val message = logger.messages.lastOrNull() ?: continue
            val tail = message.substring(message.lastIndexOf(GeofenceLogTail.DELIMITER) + GeofenceLogTail.DELIMITER.length)
            for (token in tail.split(" ")) {
                if (token.split("=", limit = 2).size != 2) {
                    throw AssertionError("$name: token '$token' is not key=value — a value contained a space")
                }
            }
        }
    }

    @Test
    fun documentedProse_expectExactStringsManualTestsGrepFor() {
        // The gate test above only proves no tail leaked; it cannot see a reworded message. The
        // rename went through it green, changing a line docs/manual-tests greps for — a tester
        // then finds nothing and reports a false failure.
        //
        // Three of the nine hallmark strings that doc lists — the ones this change set touched or
        // moved past. The other six are unpinned and would still drift silently; extending this
        // list is cheap and worth doing next time one of them is edited. Golden by design: if a
        // pinned line changes, the doc changes with it, deliberately, in the same commit.
        GeofenceDiagnostics.setEnabledForTesting(false)
        val golden = listOf<Pair<String, (GeofenceLogger) -> Unit>>(
            "Geofence 'notl_core' ENTER: queued for at-least-once delivery (WorkManager now, analytics pipeline on next foreground)"
                to { l: GeofenceLogger -> l.logTransitionAccepted("notl_core", "ENTER", 1) },
            "Geofence 'notl_core' ENTER: delivered via WorkManager (direct HTTP); removed from pending store"
                to { l: GeofenceLogger -> l.logEventDelivered("notl_core", "ENTER") },
            "Geofence 'notl_core' ENTER: published to analytics pipeline via foreground flush"
                to { l: GeofenceLogger -> l.logForegroundFlushPublished("notl_core", "ENTER") }
        )
        for ((expected, run) in golden) {
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))
            val message = logger.messages.lastOrNull()
            message.shouldNotBeNull()
            // Full equality including the tag: the doc's first instruction is
            // `adb logcat | grep -E "\[Geofence\]"`, so the prefix is part of what a tester
            // relies on. endsWith would let a TAG change pass unnoticed.
            if (message != "[Geofence] $expected") {
                throw AssertionError(
                    "prose drifted from docs/manual-tests/geofence-transition-delivery.md\n" +
                        "  expected: '$expected'\n  actual:   '$message'"
                )
            }
        }
    }

    @Test
    fun everyRecord_expectTheDeclaredVocabulary() {
        // Companion to the per-row `ev` pin above, catching what a per-row check cannot: a key
        // removed from the module entirely, or a row added to the table without being declared
        // here. It does NOT see a record that exists in the module but was never added to the
        // table — `seen` is built from the table, not from the source. `fence.cataloged` is the
        // standing proof of that limit.
        //
        // `fence.cataloged` is absent because the `apiFetchResult` row passes no regions, so
        // `logFenceCatalog` returns at its `isEmpty` guard and no catalog row is ever emitted here.
        // It carries its own `ev` assertion further down. Add a region to that row and this set
        // gains `fence.cataloged` — update both together.
        val expected = setOf(
            "api.fetch.result",
            "dispatch.ready",
            "polygon.arrival.pending",
            "polygon.arrival.expired",
            "polygon.evaluation.skipped",
            "polygon.sampling.skipped",
            "polygon.approach.stop_refused",
            "polygon.approach.request_discarded",
            "polygon.freshfix.requested",
            "polygon.freshfix.received",
            "polygon.freshfix.skipped",
            "polygon.recheck.ran",
            "polygon.recheck.skipped",
            "polygon.passive.started",
            "polygon.passive.stopped",
            "polygon.passive.received",
            "polygon.passive.skipped",
            "polygon.passive.failed",
            "api.transition.unknown",
            "containment.judged",
            "delivery.failed",
            "delivery.flush",
            "delivery.queued",
            "delivery.sent",
            "info",
            "module.init",
            "module.reset",
            "module.wake",
            "movement.rearmed",
            "identity.changed",
            "location.fix",
            "movement.registered",
            "os.callback.dropped",
            "os.callback.no_location",
            "os.callback.received",
            "os.error",
            "permission.changed",
            "polygon.approach.dropped",
            "polygon.approach.failed",
            "polygon.approach.started",
            "polygon.approach.stopped",
            "polygon.decided",
            "polygon.unchanged",
            "polygon.undecided",
            "queue.unreadable",
            "rank.evaluated",
            "rank.excluded",
            "registration.added",
            "registration.applied",
            "registration.cleared",
            "registration.failed",
            "registration.kept",
            "registration.rejected",
            "registration.removed",
            "storage.loaded",
            "storage.write.failed",
            "sync.completed",
            "sync.failed",
            "sync.skipped",
            "sync.triggered",
            "transition.accepted",
            "transition.dropped",
            "transition.suppressed",
            "transition.synthesized"
        )
        GeofenceDiagnostics.setEnabledForTesting(true)
        val seen = mutableSetOf<String>()
        for (row in invocations()) {
            val run = row.run
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))
            parseTail(logger.messages.lastOrNull() ?: "")?.get("ev")?.let { seen.add(it) }
        }
        if (seen != expected) {
            throw AssertionError(
                "vocabulary drift.\n  added:   ${(seen - expected).sorted()}\n  missing: ${(expected - seen).sorted()}"
            )
        }
    }

    // MARK: - The gate

    @Test
    fun everyRecord_givenDiagnosticsOff_expectOutputUnchangedFromBeforeInstrumentation() {
        GeofenceDiagnostics.setEnabledForTesting(false)

        for (row in invocations()) {
            val name = row.name
            val run = row.run
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))
            // Diagnostics-only entry points emit nothing at all with the gate off, which is a
            // stronger guarantee than emitting unchanged prose.
            val message = logger.messages.lastOrNull() ?: continue

            // The whole guarantee in one assertion: a customer build that ships with debug logging
            // left on sees exactly the prose it saw before this instrumentation existed. Not "sees
            // only the safe fields" — sees nothing new at all, so a field added to the tail later
            // needs no privacy review of its own.
            if (message.contains(GeofenceLogTail.DELIMITER) || message.contains("ev=")) {
                throw AssertionError("$name: emitted diagnostics with the gate off — '$message'")
            }
        }
    }

    @Test
    fun everyRecord_givenDiagnosticsOff_expectNoDiagnosticKeyAnywhere() {
        GeofenceDiagnostics.setEnabledForTesting(false)
        val logger = CapturingLogger()
        val target = GeofenceLogger(logger)
        for (row in invocations()) row.run(target)

        // Spot-checks the classes of value the harness cares about, including ones that are
        // harmless in isolation. The point is that "harmless in isolation" stopped being the test.
        for (message in logger.messages) {
            for (key in listOf("lat=", "lon=", "alt=", "spd=", "brg=", "rlat=", "rlon=", "acc=", "age=", "fixsrc=", "io=", "why=")) {
                if (message.contains(key)) throw AssertionError("$key leaked with the gate off: '$message'")
            }
        }
    }

    @Test
    fun everyRecord_givenDiagnosticsOn_expectFullDetail() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        val target = GeofenceLogger(logger)
        for (row in invocations()) row.run(target)

        val joined = logger.messages.joinToString("\n")
        joined.contains("lat=") shouldBeEqualTo true
        joined.contains("lon=") shouldBeEqualTo true
        joined.contains("fixsrc=") shouldBeEqualTo true
    }

    @Test
    fun listValues_expectSeparatorsSurviveTheTailBuilder() {
        // Regression: sanitize folds the format's separators so an untrusted id cannot split a
        // field, but it must not be applied to a value that composed those separators on purpose.
        // Folding them turned ids=a,b into ids=a_b and ranked=x:120 into ranked=x_120, which no
        // unit test noticed and a device capture did.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logRankEvaluated(
            3,
            2,
            { listOf("alpha", "beta") },
            { listOf("gamma") },
            { mapOf("alpha" to 120.0, "beta" to 340.0) }
        )
        val message = logger.messages.last()
        message.contains("ranked=alpha:120,beta:340") shouldBeEqualTo true
        message.contains("evicted=gamma") shouldBeEqualTo true
    }

    @Test
    fun proseHalf_expectIdenticalWhicheverWayTheGateIsSet() {
        // The prose is what a customer reads and what existing tests assert on. Enabling
        // diagnostics must append to it and never rewrite it.
        for (row in invocations()) {
            val name = row.name
            val run = row.run
            // Each pass is a fresh "launch" for the once-per-process module.init record.
            GeofenceDiagnostics.setEnabledForTesting(false)
            val off = CapturingLogger()
            run(GeofenceLogger(off))

            GeofenceDiagnostics.setEnabledForTesting(true)
            val on = CapturingLogger()
            run(GeofenceLogger(on))

            val onProse = on.messages.last().substringBefore(GeofenceLogTail.DELIMITER)
            // Nothing with the gate off means nothing to compare — see above.
            val offProse = off.messages.lastOrNull() ?: continue
            if (onProse != offProse) {
                throw AssertionError("$name: prose differs between gate states\n  off: $offProse\n  on:  $onProse")
            }
        }
    }

    @Test
    fun fixQuality_givenDiagnosticsOn_expectQualityAndProvenance() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        geofenceLogger.logCallbackReceived(listOf("notl_core"), "ENTER", location(), GeofenceLogTail.FixSource.OS_TRIGGER)

        val fields = parseTail(capturing.messages.last())!!
        fields["acc"] shouldBeEqualTo "48.0"
        fields["fixsrc"] shouldBeEqualTo "os_trigger"
        fields["sim"] shouldBeEqualTo "false"
        fields["age"].shouldNotBeNull()
    }

    @Test
    fun fixQuality_givenMockLocation_expectSimTrue() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        geofenceLogger.logCallbackReceived(listOf("notl_core"), "ENTER", location(mock = true), GeofenceLogTail.FixSource.OS_TRIGGER)

        // A fix injected by `adb emu geo fix` or a route driver must be distinguishable from a real
        // one, or a bench corpus and a drive corpus silently merge.
        parseTail(capturing.messages.last())!!["sim"] shouldBeEqualTo "true"
    }

    // MARK: - Derived tokens, pinned

    @Test
    fun token_givenProse_expectSnakeCase() {
        GeofenceLogTail.token("No identified user") shouldBeEqualTo "no_identified_user"
        GeofenceLogTail.token("boot-restore") shouldBeEqualTo "boot_restore"
        GeofenceLogTail.token("user changed during refresh — initial-enter synthesis skipped") shouldBeEqualTo
            "user_changed_during_refresh_initial_enter_synthesis_skipped"
        GeofenceLogTail.token("") shouldBeEqualTo "unknown"
    }

    @Test
    fun syncSkipped_givenKnownReasons_expectPinnedTokens() {
        // Every literal passed to logSyncSkipped in the geofence module. These tokens are what
        // analysis groups on, so a reworded sentence must fail here rather than quietly split one
        // bucket into two.
        val expected = mapOf(
            "no cached state to restore" to "no_cached_state_to_restore",
            "no identified user" to "no_identified_user",
            "refresh already in progress" to "refresh_already_in_progress",
            "refresh already in progress after waiting" to "refresh_already_in_progress_after_waiting",
            "reset superseded by signed-in user" to "reset_superseded_by_signed_in_user",
            "user changed during refresh" to "user_changed_during_refresh"
        )
        for ((prose, token) in expected) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logSyncSkipped(prose)
            parseTail(logger.messages.last())!!["why"] shouldBeEqualTo token
        }
    }

    @Test
    fun polygonReasons_expectPinnedTokensAndUnchangedProse() {
        // These tokens are shared with iOS, so they are pinned rather than derived from the
        // sentence: a reword here would move the bucket on Android only, and a joint query would
        // read one platform's drops as two populations.
        PolygonDropReason.entries.map { it.wire } shouldBeEqualTo listOf(
            "undescribed_shape",
            "unusable_polygon",
            "ring_unbuildable",
            "unusable_circle"
        )
        PolygonNotRankedReason.entries.map { it.wire } shouldBeEqualTo listOf("runtime_unsupported", "ring_unbuildable")
        PolygonFixRejection.entries.map { it.wire } shouldBeEqualTo listOf("no_usable_fix", "fix_too_old")
        PolygonUndecidedReason.entries.map { it.wire } shouldBeEqualTo
            listOf("accuracy_too_low", "on_boundary", "within_accuracy")

        // The prose half is the contract the other way round: these sentences shipped before the
        // tail existed, and a customer reading debug logs must still see what they saw.
        for (reason in PolygonDropReason.entries) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logPolygonDropped("notl_core", reason)
            logger.messages.last().contains("Geofence 'notl_core' dropped — polygon rejected: ${reason.detail}") shouldBeEqualTo true
            parseTail(logger.messages.last())!!["why"] shouldBeEqualTo reason.wire
            // The value, not just its presence: `sh=circle` here would read as a circle drop.
            parseTail(logger.messages.last())!!["sh"] shouldBeEqualTo "polygon"
        }
        for (reason in PolygonNotRankedReason.entries) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logPolygonRegionNotRanked("notl_core", reason)
            logger.messages.last().contains("excluded from ranking — ${reason.detail}.") shouldBeEqualTo true
            parseTail(logger.messages.last())!!["why"] shouldBeEqualTo reason.wire
            parseTail(logger.messages.last())!!["sh"] shouldBeEqualTo "polygon"
        }
        for (reason in PolygonFixRejection.entries) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logPolygonFixNotUsable(reason)
            logger.messages.last().contains("Polygon fix ignored — ${reason.detail}.") shouldBeEqualTo true
            parseTail(logger.messages.last())!!["why"] shouldBeEqualTo reason.wire
        }
    }

    @Test
    fun polygonRejections_expectTheirOwnBucketNotTheCountsTheyWouldInflate() {
        GeofenceDiagnostics.setEnabledForTesting(true)

        // A decode-time drop happens once per sync; a ranking exclusion repeats on every movement
        // trigger for the same fence. Filed under one `ev` they would inflate each other.
        val dropped = CapturingLogger()
        GeofenceLogger(dropped).logPolygonDropped("notl_core", PolygonDropReason.RING_UNBUILDABLE)
        val notRanked = CapturingLogger()
        GeofenceLogger(notRanked).logPolygonRegionNotRanked("notl_core", PolygonNotRankedReason.RING_UNBUILDABLE)

        parseTail(dropped.messages.last())!!["ev"] shouldBeEqualTo "registration.rejected"
        parseTail(notRanked.messages.last())!!["ev"] shouldBeEqualTo "rank.excluded"

        // And a refused fix is not a refused callback: `os.callback.dropped` is what makes
        // received-vs-dropped countable, and a location update has no receipt to net against.
        val fix = CapturingLogger()
        GeofenceLogger(fix).logPolygonFixNotUsable(PolygonFixRejection.NO_USABLE_FIX)
        parseTail(fix.messages.last())!!["ev"] shouldBeEqualTo "polygon.undecided"
    }

    @Test
    fun runtimeUnsupported_expectOneTokenWhicheverPhaseRefusedIt() {
        GeofenceDiagnostics.setEnabledForTesting(true)

        // A build without polygon monitoring refuses the record at decode and again at ranking,
        // from two call sites that do not share a constant. One question, so one token.
        val atDecode = CapturingLogger()
        GeofenceLogger(atDecode).logPolygonDroppedUnsupportedRuntime("notl_core")
        val atRank = CapturingLogger()
        GeofenceLogger(atRank).logPolygonRegionNotRanked("notl_core", PolygonNotRankedReason.RUNTIME_UNSUPPORTED)

        parseTail(atDecode.messages.last())!!["why"] shouldBeEqualTo "runtime_unsupported"
        parseTail(atRank.messages.last())!!["why"] shouldBeEqualTo "runtime_unsupported"
    }

    @Test
    fun queueUnreadable_expectViaSeparatesTheTwoPathsSharingTheEv() {
        GeofenceDiagnostics.setEnabledForTesting(true)

        // One ev for both paths, so `via` is the only thing separating them. Collapsed, the flush
        // trace reads as a worker wake.
        val worker = CapturingLogger()
        GeofenceLogger(worker).logEventWorkerQueueUnreadable(2, willRetry = true)
        val flush = CapturingLogger()
        GeofenceLogger(flush).logForegroundFlushQueueUnreadable()

        parseTail(worker.messages.last())!!["via"] shouldBeEqualTo "work_manager"
        parseTail(flush.messages.last())!!["via"] shouldBeEqualTo "foreground_flush"
        // Shared, so a query for the failure itself catches both.
        parseTail(worker.messages.last())!!["why"] shouldBeEqualTo "read_failed"
        parseTail(flush.messages.last())!!["why"] shouldBeEqualTo "read_failed"
    }

    @Test
    fun polygonApproachFailures_givenEachCallPath_expectTheyStayDistinguishable() {
        // An OS refusal is the environment's and replays as input; our own handler throwing is an
        // internal decision. Within each, `op` separates the two call sites that share the record.
        geofenceLogger.logPolygonApproachRequestFailed("boom", operation = "request_updates")
        parseTail(capturing.messages.last())!!["op"] shouldBeEqualTo "request_updates"
        parseTail(capturing.messages.last())!!["io"] shouldBeEqualTo "in"

        geofenceLogger.logPolygonApproachRequestFailed("boom", operation = "remove_updates")
        parseTail(capturing.messages.last())!!["op"] shouldBeEqualTo "remove_updates"

        geofenceLogger.logPolygonApproachProcessingFailed("boom", operation = "receive")
        parseTail(capturing.messages.last())!!["op"] shouldBeEqualTo "receive"
        parseTail(capturing.messages.last())!!["io"] shouldBeEqualTo "obs"

        geofenceLogger.logPolygonApproachProcessingFailed("boom", operation = "deliver")
        parseTail(capturing.messages.last())!!["op"] shouldBeEqualTo "deliver"
    }

    @Test
    fun unsupportedGeometry_givenTypeWithSeparators_expectSanitized() {
        // `sh` carries whatever the backend claimed, so on this path it is untrusted wire input
        // rather than the circle|polygon the catalog emits.
        geofenceLogger.logUnsupportedGeometryDropped("notl_core", "Multi Polygon,v2")

        val tail = parseTail(capturing.messages.last())!!
        tail["sh"] shouldBeEqualTo "Multi_Polygon_v2"
        tail["why"] shouldBeEqualTo "unknown_shape"
    }

    @Test
    fun everyLogMethod_expectATableRowOrADeclaredReason() {
        // The table cannot see a record that exists in the module but was never added to it, which
        // is how five polygon records shipped with no tail at all. This closes that loop: a new
        // `log*` method must either appear above or be named here. Empty is the goal state, so a
        // record added without a tail now fails rather than being declared away.
        val untailed = emptySet<String>()
        // Emits one record per region, so the table's `apiFetchResult` row cannot reach it — it
        // passes no regions. Covered by the `fenceCatalog_*` tests instead.
        val coveredElsewhere = setOf("logFenceCatalog")

        val declared = GeofenceLogger::class.java.declaredMethods
            .map { it.name }
            .filter { it.startsWith("log") && !it.contains('$') }
            .toSet()
        val tabled = invocations().map { it.method }.toSet()

        (declared - tabled - untailed - coveredElsewhere).sorted() shouldBeEqualTo emptyList()
        // The other direction: a row left pointing at a method that no longer exists.
        (tabled - declared).sorted() shouldBeEqualTo emptyList()
    }

    @Test
    fun sanitize_givenWhitespaceInIdentifier_expectFolded() {
        geofenceLogger.logTransitionAccepted("niagara on the lake", "ENTER", 1)

        // Workspace-authored identifiers can contain anything; the parser splits on whitespace.
        parseTail(capturing.messages.last())!!["id"] shouldBeEqualTo "niagara_on_the_lake"
    }

    @Test
    fun sanitize_givenSeparatorsInIdentifier_expectFolded() {
        // Regression: the whitespace test above passes whether or not token fields are sanitized,
        // because tail() folds whitespace for every value. It never covered the characters the
        // format itself uses, and 20 `id` call sites went unprotected behind it.
        for (raw in listOf("store,north", "a=b", "aisle:3", "wing|west")) {
            val logger = CapturingLogger()
            GeofenceLogger(logger).logTransitionAccepted(raw, "ENTER", 1)

            val tail = parseTail(logger.messages.last())
            tail.shouldNotBeNull()
            tail["id"].shouldNotBeNull()
            tail["id"]!!.none { it in charArrayOf('=', ',', ':', '|') } shouldBeEqualTo true
            tail["t"] shouldBeEqualTo "enter"
        }
    }

    @Test
    fun composedValues_givenSeparatorsOnPurpose_expectPreserved() {
        // The other half of the same contract: sanitizing by default must not touch the values
        // that build their own structure.
        geofenceLogger.logRankEvaluated(
            candidates = 3,
            selectedCount = 2,
            selected = { listOf("alpha", "beta") },
            evicted = { listOf("gamma") },
            edgeDistances = { mapOf("alpha" to 120.0, "beta" to 340.0) }
        )
        val tail = parseTail(capturing.messages.last())!!
        tail["ranked"] shouldBeEqualTo "alpha:120,beta:340"
        tail["evicted"] shouldBeEqualTo "gamma"
    }

    @Test
    fun list_givenMoreThanLimit_expectTruncationMarker() {
        val values = (1..30).map { "id$it" }
        GeofenceLogTail.list(values, limit = 25)!!.endsWith(",+5") shouldBeEqualTo true
        GeofenceLogTail.list(emptyList()).shouldBeNull()
    }

    @Test
    fun expensiveFields_givenDiagnosticsOff_expectNeverEvaluated() {
        // The gate's worth on these two paths is that the work is never *done*, not merely never
        // printed. Every other test here asserts on output, so moving the gate below the lambda
        // call would leave them all green while a background wake still pays for a distance map
        // over every candidate and a deserialization of the cached region list.
        GeofenceDiagnostics.setEnabledForTesting(false)
        val logger = CapturingLogger()
        val subject = GeofenceLogger(logger)
        var selectedCalls = 0
        var evictedCalls = 0
        var distanceCalls = 0
        var regionCountCalls = 0
        var catalogCalls = 0

        subject.logRankEvaluated(
            candidates = 50,
            selectedCount = 19,
            selected = { selectedCalls++; listOf("alpha") },
            evicted = { evictedCalls++; listOf("beta") },
            edgeDistances = { distanceCalls++; mapOf("alpha" to 120.0) }
        )
        subject.logStorageLoaded(regionCount = { regionCountCalls++; 100 }, hasAnchor = true)
        subject.logApiFetchResult(1, 10L) { catalogCalls++; listOf(catalogRegion()) }

        selectedCalls shouldBeEqualTo 0
        evictedCalls shouldBeEqualTo 0
        distanceCalls shouldBeEqualTo 0
        regionCountCalls shouldBeEqualTo 0
        // The catalog re-decodes and re-validates every polygon ring the mapper is about to build
        // anyway; with the gate off that is a background fetch paying twice for rows nobody writes.
        catalogCalls shouldBeEqualTo 0

        // Proves the counts above are zero because of the gate, not because the lambdas are
        // unreachable. logStorageLoaded is diagnostics-only, so it also gains its prose here.
        GeofenceDiagnostics.setEnabledForTesting(true)
        subject.logRankEvaluated(
            candidates = 50,
            selectedCount = 19,
            selected = { selectedCalls++; listOf("alpha") },
            evicted = { evictedCalls++; listOf("beta") },
            edgeDistances = { distanceCalls++; mapOf("alpha" to 120.0) }
        )
        subject.logStorageLoaded(regionCount = { regionCountCalls++; 100 }, hasAnchor = true)
        subject.logApiFetchResult(1, 10L) { catalogCalls++; listOf(catalogRegion()) }

        selectedCalls shouldBeEqualTo 1
        evictedCalls shouldBeEqualTo 1
        distanceCalls shouldBeEqualTo 1
        regionCountCalls shouldBeEqualTo 1
        catalogCalls shouldBeEqualTo 1
    }

    private fun polygonCatalogRegion(
        vertexCount: Int = 4,
        baseRadiusMeters: Double? = 900.0
    ) = GeofenceCatalogEntry(
        id = "poly-1",
        name = "Polygon Fence",
        geosetIds = listOf("4471"),
        shape = "polygon",
        latitude = 25.109908,
        longitude = 55.184004,
        // The backend's own circle. What GMS registers is this plus our platform margin, and the
        // catalog must never report that one.
        radiusMeters = baseRadiusMeters,
        vertices = List(vertexCount) { index ->
            PolygonCoordinate(25.10 + index / 10_000.0, 55.18 + index / 10_000.0)
        },
        transitionTypes = listOf("enter", "exit")
    )

    private fun catalogRegion(
        id: String = "11125",
        name: String? = "Momo Dubai Test"
    ) = GeofenceCatalogEntry(
        id = id,
        name = name,
        geosetIds = listOf("4471", "9002"),
        shape = "circle",
        latitude = 25.109908,
        longitude = 55.184004,
        radiusMeters = 150.0,
        vertices = null,
        transitionTypes = listOf("enter", "exit")
    )

    @Test
    fun fenceCatalog_givenNameWithSeparators_expectSanitizedButReadable() {
        // A workspace-authored name is untrusted text in a format whose only structure is spaces
        // and `=`. Left raw, `Momo Dubai Test` would split into three bogus fields.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(catalogRegion(name = "Momo Dubai, Test=1")) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["name"].shouldNotBeNull()
        // Still recognisable to a human reading the log, which is half the point of logging it.
        fields["name"]!!.contains("Momo") shouldBeEqualTo true
        fields["name"]!!.contains(" ") shouldBeEqualTo false
        fields["name"]!!.contains("=") shouldBeEqualTo false
    }

    /**
     * Deliberately absent from [invocations]: that table asserts the gated-*tail* contract, where
     * the gate strips detail and leaves the prose identical. The catalog is a different kind of
     * record — it exists only for diagnostics, so the gate removes it entirely. Emitting bare
     * "catalogued" lines to every customer's Logcat would be noise, and moving the detail into the
     * prose to satisfy the table would leak coordinates with the gate off. These tests pin the same
     * contract the table would have.
     */
    @Test
    fun fenceCatalog_expectMachineKeyAndReplayClassification() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(catalogRegion()) }

        val message = logger.messages.last()
        message.startsWith("[Geofence] ") shouldBeEqualTo true
        val fields = parseTail(message)
        fields.shouldNotBeNull()
        fields["ev"] shouldBeEqualTo "fence.cataloged"
        fields["io"] shouldBeEqualTo "in"
        for (key in listOf("id", "name", "gs", "sh", "lat", "lon", "rad", "tt")) {
            if (fields[key] == null) throw AssertionError("missing $key= in '$message'")
        }
    }

    @Test
    fun fenceCatalog_givenPolygon_expectShapeVertexCountAndRing() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(polygonCatalogRegion(vertexCount = 4)) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["sh"] shouldBeEqualTo "polygon"
        fields["nv"] shouldBeEqualTo "4"
        // lat_lon pairs in SDK order, comma separated, commas intact.
        fields["ring"]?.split(",")?.size shouldBeEqualTo 4
        fields["ring"]?.startsWith("25.10000_55.18000") shouldBeEqualTo true
    }

    @Test
    fun fenceCatalog_givenPolygon_expectBackendRadiusNotTheRegisteredOne() {
        // The registered radius carries our platform margin. Emitting it would read as a
        // cross-platform discrepancy that is only padding, and overstates the fence by a kilometre.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(polygonCatalogRegion(baseRadiusMeters = 900.0)) }

        parseTail(logger.messages.last())?.get("rad") shouldBeEqualTo "900"
    }

    @Test
    fun fenceCatalog_givenPolygonWithoutBackendRadius_expectRadOmittedNotPadded() {
        // The mapper drops such a polygon, and the catalog now runs before it, so this row is what
        // a capture actually gets: absent is recoverable, the padded radius is the bug this had.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        val region = polygonCatalogRegion().copy(radiusMeters = null)
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(region) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["sh"] shouldBeEqualTo "polygon"
        fields["rad"].shouldBeNull()
    }

    @Test
    fun fenceCatalog_givenCircle_expectShapeCircleAndNoRing() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(catalogRegion()) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["sh"] shouldBeEqualTo "circle"
        fields["nv"].shouldBeNull()
        fields["ring"].shouldBeNull()
        fields["rad"] shouldBeEqualTo "150"
    }

    @Test
    fun fenceCatalog_givenRingLongerThanTheCap_expectCountStaysAuthoritative() {
        // A truncated ring still looks like a valid polygon, so the count is what lets a consumer
        // notice and refuse instead of computing membership against part of the shape.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(polygonCatalogRegion(vertexCount = 30)) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["nv"] shouldBeEqualTo "30"
        fields["ring"]?.endsWith(",+5") shouldBeEqualTo true
    }

    @Test
    fun fenceCatalog_expectOneRecordPerFence() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(3, 10L) {
            listOf(catalogRegion(id = "1"), catalogRegion(id = "2"), catalogRegion(id = "3"))
        }
        logger.messages.count { it.contains("ev=fence.cataloged") } shouldBeEqualTo 3
    }

    @Test
    fun fenceCatalog_givenGeosetList_expectSeparatorsPreserved() {
        // `gs` and `tt` compose commas on purpose, like `ids` and `ranked` — they must opt out of
        // sanitising or the list collapses into one token.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(catalogRegion()) }

        val fields = parseTail(logger.messages.last())
        fields.shouldNotBeNull()
        fields["gs"] shouldBeEqualTo "4471,9002"
        fields["tt"] shouldBeEqualTo "enter,exit"
        fields["lat"] shouldBeEqualTo "25.10991"
        fields["rad"] shouldBeEqualTo "150"
    }

    @Test
    fun fenceCatalog_givenDiagnosticsOff_expectNoCatalogRecords() {
        // The catalog carries no prose worth emitting on its own; with the gate off it must not
        // exist at all, not merely lose its tail.
        GeofenceDiagnostics.setEnabledForTesting(false)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L) { listOf(catalogRegion()) }

        logger.messages.none { it.contains("catalogued") } shouldBeEqualTo true
    }

    /**
     * Deliberately absent from [invocations], for the same reason as the catalog above: that table
     * asserts the gated-*tail* contract, where the gate strips detail and leaves the prose
     * identical. This record is gated whole — a bare "ready after 12ms" with no `ev` says nothing
     * a reader could act on, and it fires once per broadcast (72 times in nine hours on the
     * 2026-09-14 drive), so leaving the prose behind would be volume with nothing in it. These
     * tests pin the same contract the table would have.
     */
    @Test
    fun dispatchReady_expectMachineKeyAndObservationClassification() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logDispatchReady(12L)

        val message = logger.messages.last()
        message.startsWith("[Geofence] ") shouldBeEqualTo true
        val fields = parseTail(message)
        fields.shouldNotBeNull()
        fields["ev"] shouldBeEqualTo "dispatch.ready"
        // `obs`, never `out`: how long the SDK took to be ready is not something a user could
        // notice, so replay must never assert on it.
        fields["io"] shouldBeEqualTo "obs"
        fields["ms"] shouldBeEqualTo "12"
    }

    @Test
    fun dispatchReady_givenDiagnosticsOff_expectNoRecord() {
        GeofenceDiagnostics.setEnabledForTesting(false)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logDispatchReady(12L)

        logger.messages.none { it.contains("Crossing pipeline ready") } shouldBeEqualTo true
    }
}
