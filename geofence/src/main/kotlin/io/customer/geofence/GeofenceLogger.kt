package io.customer.geofence

import android.location.Location
import io.customer.geofence.GeofenceLogTail.bool
import io.customer.geofence.GeofenceLogTail.composedList
import io.customer.geofence.GeofenceLogTail.int
import io.customer.geofence.GeofenceLogTail.list
import io.customer.geofence.GeofenceLogTail.num
import io.customer.geofence.GeofenceLogTail.token
import io.customer.sdk.core.util.Logger

/**
 * How the SDK came to be running.
 *
 * Nothing marks a cold background wake today, which makes it impossible to tell "the SDK was never
 * running" apart from "the SDK ran and decided not to act" when reading a drive afterwards.
 */
internal enum class GeofenceLaunchReason(val wire: String) {
    APP_START("app_start"),
    BOOT_RESTORE("boot_restore")
}

/**
 * Structured logger for geofence operations, tagged for logcat filtering.
 *
 * Every record carries a ` || key=value` tail after its human-readable prose: `ev=` is a stable
 * machine key (prose is what gets reworded; `ev` is the contract) and `io=` classifies the record
 * for replay. Pre-existing prose is unchanged; records added by this work emit their prose whether
 * or not diagnostics are on — only the tail is gated.
 *
 * Reason tokens are **derived** from the existing prose rather than replacing it with an enum.
 * That keeps every `reason: String` signature exactly as it was — `logSyncSkipped` alone has 20
 * test references — while still yielding a stable `why=` for tooling. The specific tokens are
 * pinned by `GeofenceLogTailTest` so a reworded sentence fails loudly instead of silently changing
 * what analysis groups on.
 */
internal class GeofenceLogger(private val logger: Logger) {

    /** Short name for [GeofenceLogTail.tail]; the call sites below are dense with it. */
    private fun tail(
        ev: String,
        io: GeofenceLogIo,
        fields: List<Pair<String, String?>> = emptyList()
    ): String = GeofenceLogTail.tail(ev, io, fields)

    // MARK: - Registration

    fun logGeofencesRegistered(count: Int) {
        logger.debug(
            "Registered $count geofences with OS" +
                tail("registration.added", GeofenceLogIo.OUTPUT, listOf("nadd" to int(count))),
            tag = TAG
        )
    }

    /**
     * Which regions the OS is actually monitoring, by identifier.
     *
     * Counts alone cannot answer the first question anyone asks of a drive that missed a crossing —
     * "was this geofence even being monitored when I drove through it?" — so the identifiers travel
     * too. A separate method rather than widening [logSyncSucceeded], whose five `verify` blocks
     * would all need updating for no benefit.
     */
    fun logRegionsRegisteredIds(ids: List<String>, movementTriggerId: String?) {
        logger.debug(
            "Monitoring ${ids.size} region(s) with the OS" +
                tail(
                    "registration.applied",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "n" to int(ids.size),
                        "ids" to list(ids.sorted()),
                        "mvmt" to movementTriggerId
                    )
                ),
            tag = TAG
        )
    }

    fun logBusinessGeofencesKept(count: Int) {
        logger.debug(
            "Kept $count business geofences unchanged in OS; skipped re-upsert to avoid GMS state reconciliation" +
                tail(
                    "registration.kept",
                    GeofenceLogIo.OUTPUT,
                    listOf("nkeep" to int(count), "why" to "unchanged")
                ),
            tag = TAG
        )
    }

    fun logGeofencesRemoved(count: Int) {
        logger.debug(
            "Removed $count geofences from OS" +
                tail("registration.removed", GeofenceLogIo.OUTPUT, listOf("nrem" to int(count))),
            tag = TAG
        )
    }

    fun logGeofencesCleared() {
        logger.debug(
            "Cleared all geofences from OS" +
                tail("registration.cleared", GeofenceLogIo.OUTPUT, listOf("why" to "cleared")),
            tag = TAG
        )
    }

    fun logRegistrationFailed(message: String?) {
        logger.error(
            "Failed to register geofences: $message" +
                tail(
                    "registration.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "why" to token(message ?: "unknown"))
                ),
            tag = TAG
        )
    }

    fun logRemovalFailed(message: String?) {
        logger.error(
            "Failed to remove geofences: $message" +
                tail(
                    "registration.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "op" to "remove", "why" to token(message ?: "unknown"))
                ),
            tag = TAG
        )
    }

    fun logInvalidRegionDropped(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' dropped — invalid coordinates or radius, not registerable with the OS" +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "why" to "invalid_geometry")
                ),
            tag = TAG
        )
    }

    fun logRegionMappingFailed(geofenceId: String, message: String?) {
        logger.error(
            "Geofence '$geofenceId' dropped — mapping failed unexpectedly: $message" +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "why" to token(message ?: "mapping_failed"))
                ),
            tag = TAG
        )
    }

    /**
     * The N-of-M selection, which happens silently today: a geofence never registered because it
     * ranked past the cap is indistinguishable from one registered that simply never fired.
     *
     * [selected], [evicted] and [edgeDistances] are lambdas: producing them costs a distance
     * computation per region plus a filter over every candidate, on a background wake path, and
     * none of it is wanted unless the tail will carry it.
     */
    fun logRankEvaluated(
        candidates: Int,
        selectedCount: Int,
        selected: () -> List<String>,
        evicted: () -> List<String>,
        edgeDistances: () -> Map<String, Double>
    ) {
        val detail = if (GeofenceDiagnostics.isEnabled) {
            val distances = edgeDistances()
            val ranked = selected().map { id ->
                distances[id]?.let { "${GeofenceLogTail.sanitize(id)}:${it.toInt()}" } ?: GeofenceLogTail.sanitize(id)
            }
            tail(
                "rank.evaluated",
                GeofenceLogIo.OUTPUT,
                listOf(
                    "ncand" to int(candidates),
                    "n" to int(selectedCount),
                    "ranked" to composedList(ranked),
                    "evicted" to list(evicted())
                )
            )
        } else {
            ""
        }
        logger.debug("Ranked $candidates candidate(s), selected $selectedCount" + detail, tag = TAG)
    }

    /** The re-centred movement bubble's own geometry, derived from the device's position. */
    fun logMovementTriggerRegistered(latitude: Double, longitude: Double, radiusMeters: Double) {
        logger.debug(
            "Movement trigger registered with radius ${radiusMeters.toInt()} m" +
                tail(
                    "movement.registered",
                    GeofenceLogIo.OUTPUT,
                    GeofenceLogTail.position(latitude, longitude).map { (k, v) ->
                        (if (k == "lat") "rlat" else if (k == "lon") "rlon" else k) to v
                    } + listOf("rad" to num(radiusMeters, 0))
                ),
            tag = TAG
        )
    }

    // MARK: - Permissions and lifecycle

    fun logMissingPermission(permission: String) {
        logger.error(
            "Cannot register geofences: $permission not granted. Host app must request this permission." +
                tail(
                    "permission.changed",
                    GeofenceLogIo.OBSERVATION,
                    listOf("perm" to token(permission), "why" to "not_granted")
                ),
            tag = TAG
        )
    }

    fun logBackgroundDeliveryUnavailable(reason: String) {
        logger.info(
            "Geofence sync ($reason): ACCESS_BACKGROUND_LOCATION not granted — transitions will only fire while the app is in the foreground" +
                tail(
                    "permission.changed",
                    GeofenceLogIo.OBSERVATION,
                    listOf("perm" to "foreground_only", "ctx" to token(reason))
                ),
            tag = TAG
        )
    }

    /** Process start and why. A background wake and a user opening the app look identical today. */
    /**
     * The module came up. A cold wake announces itself separately via [logModuleWoke] rather than
     * racing this one for a single record — the OS decides their order, not us.
     */
    fun logModuleInitialized(launchReason: GeofenceLaunchReason) {
        logger.info(
            "Geofence module initialized (${launchReason.wire})" +
                tail("module.init", GeofenceLogIo.OBSERVATION, listOf("launch" to launchReason.wire)),
            tag = TAG
        )
    }

    /** The process was started *by* something — a boot restore today. */
    fun logModuleWoke(launchReason: GeofenceLaunchReason) {
        logger.info(
            "Geofence module woken (${launchReason.wire})" +
                tail("module.wake", GeofenceLogIo.OBSERVATION, listOf("launch" to launchReason.wire)),
            tag = TAG
        )
    }

    fun logMissingLocationModule() {
        logger.error(
            "ModuleGeofence requires ModuleLocation to be registered alongside it. Add ModuleLocation to CustomerIOConfigBuilder; geofencing will not function until then." +
                tail(
                    "module.init",
                    GeofenceLogIo.OBSERVATION,
                    listOf("ok" to bool(false), "why" to "missing_location_module")
                ),
            tag = TAG
        )
    }

    fun logGeofenceStateResetOnSignOut() {
        logger.debug(
            "Geofence state reset on user sign-out: clearing persisted regions and OS registrations" +
                tail("module.reset", GeofenceLogIo.OUTPUT, listOf("why" to "sign_out")),
            tag = TAG
        )
    }

    // MARK: - OS callbacks

    /**
     * A crossing as the OS reported it, with the fix the OS computed for it.
     *
     * Android is better placed than iOS here: `GeofencingEvent.triggeringLocation` is a real fix
     * attached to the crossing, where CoreLocation supplies no position with a geofence event at
     * all. Logged at the receiver, before any routing decision, and before the whole `Location` is
     * narrowed to a latitude and longitude pair.
     */
    fun logCallbackReceived(
        geofenceIds: List<String>,
        transitionName: String,
        location: Location?,
        source: GeofenceLogTail.FixSource
    ) {
        logger.debug(
            "OS reported $transitionName for ${geofenceIds.size} region(s)" +
                tail(
                    "os.callback.received",
                    GeofenceLogIo.INPUT,
                    listOf("ids" to list(geofenceIds), "n" to int(geofenceIds.size), "t" to token(transitionName)) +
                        GeofenceLogTail.fixQuality(location, source) +
                        GeofenceLogTail.position(location)
                ),
            tag = TAG
        )
    }

    fun logTransitionWithoutLocation() {
        logger.debug(
            "Geofence transition fired but OS provided no triggering location; a movement-trigger refresh cannot run without it" +
                tail(
                    "os.callback.no_location",
                    GeofenceLogIo.OBSERVATION,
                    listOf("fixsrc" to GeofenceLogTail.FixSource.NONE.wire, "why" to "no_triggering_location")
                ),
            tag = TAG
        )
    }

    fun logUnknownTransition(transitionType: Int) {
        logger.debug(
            "Ignoring geofence transition type=$transitionType (only ENTER and EXIT are tracked)" +
                tail(
                    "os.callback.dropped",
                    GeofenceLogIo.INPUT,
                    listOf("gms" to int(transitionType), "why" to "unsupported_transition_type")
                ),
            tag = TAG
        )
    }

    fun logMovementTriggerIgnoredNonExit(transitionName: String) {
        logger.debug(
            "Movement trigger geofence fired with transition=$transitionName; only EXIT triggers a sync" +
                tail(
                    "os.callback.dropped",
                    GeofenceLogIo.INPUT,
                    listOf("t" to token(transitionName), "why" to "movement_trigger_not_exit")
                ),
            tag = TAG
        )
    }

    fun logReceiverSkipped(reason: String) {
        logger.debug(
            "Geofence receiver skipped: $reason" +
                tail("os.callback.dropped", GeofenceLogIo.INPUT, listOf("why" to token(reason))),
            tag = TAG
        )
    }

    fun logGeofencingError(errorCode: Int) {
        logger.error(
            "OS reported geofencing error (code=$errorCode); see GeofenceStatusCodes for meaning" +
                tail(
                    "os.error",
                    GeofenceLogIo.INPUT,
                    listOf("ok" to bool(false), "code" to int(errorCode))
                ),
            tag = TAG
        )
    }

    // MARK: - Transitions

    /**
     * The position for this crossing is **not** repeated here.
     *
     * It lives on the `os.callback.received` record the receiver writes for the whole broadcast,
     * which carries the OS's triggering fix and the ids it applied to — join on `id` within the
     * same broadcast. Threading the `Location` down to this call site instead would mean widening
     * `dispatchTransition`, which has 78 test references, for information already recorded.
     */
    fun logTransitionEmitting(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: queued for at-least-once delivery (WorkManager now, analytics pipeline on next foreground)" +
                tail(
                    "transition.emitted",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName))
                ),
            tag = TAG
        )
    }

    fun logTransitionSuppressed(
        geofenceId: String,
        transitionName: String,
        cooldownRemainingSeconds: Double? = null
    ) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: suppressed — same transition fired within the cooldown window" +
                tail(
                    "transition.suppressed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "why" to "cooldown",
                        "cd" to num(cooldownRemainingSeconds)
                    )
                ),
            tag = TAG
        )
    }

    fun logInitialEnterInside(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId': device already inside a newly-registered fence — synthesizing ENTER (GMS INITIAL_TRIGGER_ENTER is unreliable)" +
                tail(
                    "transition.synthesized",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to "enter", "why" to "initial_enter_inside")
                ),
            tag = TAG
        )
    }

    fun logTransitionDroppedUnknownId(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' transition dropped — id not in registered store" +
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "why" to "unknown_id")
                ),
            tag = TAG
        )
    }

    fun logEnterDroppedAlreadyReported(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' ENTER: dropped — already reported as entered and no exit since, so the OS is re-reporting a state we already sent" +
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to "enter", "why" to "already_reported")
                ),
            tag = TAG
        )
    }

    fun logExitDroppedNeverEntered(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' EXIT: dropped — no record of the device being inside, so the OS is reconciling its own state" +
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to "exit", "why" to "never_entered")
                ),
            tag = TAG
        )
    }

    fun logTransitionDroppedAnonymous(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: dropped — no identified user (geofencing is identified-only)" +
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "no_identified_user")
                ),
            tag = TAG
        )
    }

    // MARK: - Sync

    fun logSyncTriggered(reason: String) {
        logger.debug(
            "Geofence sync triggered: $reason" +
                tail("sync.triggered", GeofenceLogIo.OUTPUT, listOf("why" to token(reason))),
            tag = TAG
        )
    }

    fun logSyncSkipped(reason: String) {
        logger.debug(
            "Geofence sync skipped: $reason" +
                tail("sync.skipped", GeofenceLogIo.OUTPUT, listOf("why" to token(reason))),
            tag = TAG
        )
    }

    fun logSyncSkippedNoLocation(reason: String) {
        logger.debug(
            "Geofence sync skipped ($reason): no location available" +
                tail(
                    "sync.skipped",
                    GeofenceLogIo.OUTPUT,
                    listOf("why" to "no_location", "ctx" to token(reason))
                ),
            tag = TAG
        )
    }

    fun logSyncSkippedInvalidLocation(reason: String, latitude: Double, longitude: Double) {
        logger.error(
            "Geofence sync skipped ($reason): OS reported an unusable fix ($latitude, $longitude)" +
                tail(
                    "sync.skipped",
                    GeofenceLogIo.OUTPUT,
                    listOf("why" to "invalid_location", "ctx" to token(reason)) +
                        GeofenceLogTail.position(latitude, longitude)
                ),
            tag = TAG
        )
    }

    fun logSyncSkippedNoPermission(reason: String) {
        logger.debug(
            "Geofence sync skipped ($reason): location permissions not granted" +
                tail(
                    "sync.skipped",
                    GeofenceLogIo.OUTPUT,
                    listOf("why" to "no_permission", "ctx" to token(reason))
                ),
            tag = TAG
        )
    }

    fun logSyncSkippedFresh() {
        logger.debug(
            "Geofence sync skipped: last successful sync is still within the freshness window" +
                tail("sync.skipped", GeofenceLogIo.OUTPUT, listOf("why" to "within_freshness_window")),
            tag = TAG
        )
    }

    fun logSyncFailed(message: String?) {
        logger.error(
            "Geofence sync failed: $message" +
                tail(
                    "sync.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "why" to token(message ?: "unknown"))
                ),
            tag = TAG
        )
    }

    fun logSyncSucceeded(count: Int, movementTriggerRegistered: Boolean, elapsedMillis: Long? = null) {
        val trigger = if (movementTriggerRegistered) {
            " + 1 movement trigger"
        } else {
            "; monitoring disabled (max business geofences is 0)"
        }
        logger.debug(
            "Geofence sync succeeded: $count regions registered$trigger" +
                tail(
                    "sync.completed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "n" to int(count),
                        "mvmt" to bool(movementTriggerRegistered),
                        "ms" to elapsedMillis?.toString()
                    )
                ),
            tag = TAG
        )
    }

    /** Outcome of a nearby-geofence fetch. An **input**: replay feeds the response back. */
    fun logApiFetchResult(
        returnedCount: Int,
        elapsedMillis: Long?,
        regions: List<GeofenceRegion> = emptyList()
    ) {
        logger.debug(
            "Fetched $returnedCount nearby geofence(s) from the server" +
                tail(
                    "api.fetch.result",
                    GeofenceLogIo.INPUT,
                    listOf(
                        "ok" to bool(true),
                        "n" to int(returnedCount),
                        "ms" to elapsedMillis?.toString()
                    )
                ),
            tag = TAG
        )
        logFenceCatalog(regions)
    }

    /**
     * One record per fetched fence, describing the circle the server sent.
     *
     * Without this a capture names fences only by opaque id: a replay cannot place them, and nobody
     * reading the log can tell which geoset a crossing belonged to. Re-fetching the geometry from
     * the workspace later is not equivalent — fences move, and a drive replayed months on would
     * silently get today's circles instead of the ones it actually ran against.
     *
     * Gated whole rather than relying on `tail()` returning empty, because these records carry no
     * prose worth emitting on their own — with diagnostics off they should not exist at all.
     */
    private fun logFenceCatalog(regions: List<GeofenceRegion>) {
        if (regions.isEmpty() || !GeofenceDiagnostics.isEnabled) return
        for (region in regions) {
            logger.debug(
                "Geofence '${region.id}' catalogued" +
                    tail(
                        "fence.cataloged",
                        GeofenceLogIo.INPUT,
                        listOf(
                            "id" to region.id,
                            // Sanitized like any other value: a workspace-authored name can contain
                            // spaces, commas and `=`, all of which would break the parser's split.
                            "name" to region.name,
                            "gs" to composedList(region.geosetIds),
                            "lat" to num(region.latitude, 5),
                            "lon" to num(region.longitude, 5),
                            "rad" to num(region.radius, 0),
                            "tt" to composedList(region.transitionTypes.map { it.name.lowercase() })
                        )
                    ),
                tag = TAG
            )
        }
    }

    fun logApiFetchFailed(message: String?) {
        logger.error(
            "Sync fetch failed: $message" +
                tail(
                    "api.fetch.result",
                    GeofenceLogIo.INPUT,
                    listOf("ok" to bool(false), "why" to token(message ?: "unknown"))
                ),
            tag = TAG
        )
    }

    fun logUnknownApiTransitionType(value: String) {
        logger.error(
            "API response contained unknown transition_type='$value' (expected enter/exit). Region's affected types dropped — check SDK / backend version alignment." +
                tail(
                    "api.transition.unknown",
                    GeofenceLogIo.INPUT,
                    listOf("ok" to bool(false), "why" to "unknown_transition_type", "value" to token(value))
                ),
            tag = TAG
        )
    }

    fun logMovementRearmedAfterFailedRefresh() {
        logger.debug(
            "Movement refresh failed; re-ranking from cache to re-arm the movement trigger" +
                tail("movement.rearmed", GeofenceLogIo.OUTPUT, listOf("why" to "refresh_failed")),
            tag = TAG
        )
    }

    // MARK: - Storage

    /** What survived a cold start. Answers whether a background wake had anything to work from. */
    /**
     * Diagnostics-only, and the gate lives here rather than at the call sites: the count is in the
     * prose, and producing it costs a deserialization of the cached region list on a background
     * wake. A lambda keeps that read off the path entirely when nothing will read the record.
     */
    fun logStorageLoaded(regionCount: () -> Int, hasAnchor: Boolean) {
        if (!GeofenceDiagnostics.isEnabled) return
        val count = regionCount()
        logger.debug(
            "Loaded $count cached region(s) from storage" +
                tail(
                    "storage.loaded",
                    GeofenceLogIo.INPUT,
                    listOf("n" to int(count), "anchor" to bool(hasAnchor))
                ),
            tag = TAG
        )
    }

    fun logPersistFailed(geofenceId: String, transitionName: String) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: failed to persist pending transition — skipped delivery and rolled back cooldown so a later crossing can retry" +
                tail(
                    "storage.write.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "ok" to bool(false))
                ),
            tag = TAG
        )
    }

    // MARK: - Delivery
    //
    // Classified `out` and namespaced under `delivery.` so replay can drop the whole family: what
    // replay checks is that the SDK *emitted* a transition, not that it reached the backend.

    fun logEventDeliveryRetryable(geofenceId: String, transitionName: String, message: String?) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: HTTP delivery hit network error ($message); WorkManager will retry" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "retry" to bool(true),
                        "why" to token(message ?: "network_error")
                    )
                ),
            tag = TAG
        )
    }

    fun logEventDeliveryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: HTTP delivery failed and will not retry — $message" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "retry" to bool(false),
                        "why" to token(message ?: "unknown")
                    )
                ),
            tag = TAG
        )
    }

    fun logEventInvalidInput(geofenceId: String?, transitionName: String?) {
        logger.error(
            "Geofence event worker dropped: required field missing (geofenceId='$geofenceId', transition='$transitionName')" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "why" to "missing_required_field")
                ),
            tag = TAG
        )
    }

    fun logEventDeliveryDeferredAnonymous(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: no identified user at queue time — HTTP path deferred to foreground flush (analytics pipeline)" +
                tail(
                    "delivery.queued",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "no_identified_user")
                ),
            tag = TAG
        )
    }

    fun logEventDelivered(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: delivered via WorkManager (direct HTTP); removed from pending store" +
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "via" to "work_manager")
                ),
            tag = TAG
        )
    }

    fun logEventDeliverySkippedAlreadyDelivered(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: worker skipped — entry no longer in store (already delivered via the analytics pipeline)" +
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "already_delivered")
                ),
            tag = TAG
        )
    }

    fun logEventWorkerEntryMissing(key: String) {
        logger.debug(
            "Geofence event worker skipped: no pending entry for '$key' (already delivered via the analytics pipeline)" +
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OUTPUT,
                    listOf("key" to key, "why" to "already_delivered")
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushSnapshot(count: Int) {
        logger.debug(
            "Geofence foreground flush: $count pending transition(s) to hand off to the analytics pipeline" +
                tail(
                    "delivery.flush",
                    GeofenceLogIo.OUTPUT,
                    listOf("n" to int(count), "phase" to "start")
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushCancelledWorkManager(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: cancelled pending WorkManager delivery before flush" +
                tail(
                    "delivery.flush",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "cancelled_work_manager")
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushPublished(geofenceId: String, transitionName: String) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: published to analytics pipeline via foreground flush" +
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "via" to "foreground_flush")
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushEntryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: foreground flush failed; left in store for next flush — $message" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "via" to "foreground_flush",
                        "why" to token(message ?: "unknown")
                    )
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushComplete(count: Int) {
        logger.debug(
            "Geofence foreground flush complete: $count transition(s) handed off this run" +
                tail(
                    "delivery.flush",
                    GeofenceLogIo.OUTPUT,
                    listOf("n" to int(count), "phase" to "complete", "ok" to bool(true))
                ),
            tag = TAG
        )
    }

    fun logAsyncDeliveryFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: async delivery failed unexpectedly; left in pending store for the foreground flush — $message" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "why" to token(message ?: "async_failure")
                    )
                ),
            tag = TAG
        )
    }

    fun logSchedulerFailed(geofenceId: String, transitionName: String, message: String?) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: WorkManager scheduling failed; left in pending store for the foreground flush — $message" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OUTPUT,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "why" to "scheduler_failed",
                        "detail" to token(message ?: "unknown")
                    )
                ),
            tag = TAG
        )
    }

    companion object {
        private const val TAG = "Geofence"
    }
}
