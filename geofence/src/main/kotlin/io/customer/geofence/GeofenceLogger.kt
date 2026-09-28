package io.customer.geofence

import android.location.Location
import io.customer.geofence.GeofenceLogTail.bool
import io.customer.geofence.GeofenceLogTail.composedList
import io.customer.geofence.GeofenceLogTail.int
import io.customer.geofence.GeofenceLogTail.list
import io.customer.geofence.GeofenceLogTail.num
import io.customer.geofence.GeofenceLogTail.token
import io.customer.geofence.polygon.PolygonCoordinate
import io.customer.geofence.polygon.PolygonUndecidedReason
import io.customer.sdk.core.util.Logger

/**
 * How the SDK came to be running, so a capture can tell "never ran" from "ran and chose not to act".
 */
internal enum class GeofenceLaunchReason(val wire: String) {
    APP_START("app_start"),
    BOOT_RESTORE("boot_restore")
}

/**
 * Why a polygon record never became a monitored fence.
 *
 * [wire] is the fixed machine token and [detail] the prose, kept apart so a reword never moves the
 * bucket analysis groups on. iOS emits the same tokens.
 */
internal enum class PolygonDropReason(val wire: String, val detail: String) {
    UNDESCRIBED_SHAPE("undescribed_shape", "shape discriminator is missing or inconsistent"),
    UNUSABLE_POLYGON("unusable_polygon", "polygon geometry or enclosing circle is missing"),
    RING_UNBUILDABLE("ring_unbuildable", "ring is malformed, unsupported or fails validation"),
    UNUSABLE_CIRCLE(
        "unusable_circle",
        "enclosing circle is missing, its centre is out of range, or its radius is unusable"
    )
}

/**
 * Why a cached polygon was left out of a ranking pass, and so out of the registration batch.
 *
 * Android-only tokens. `ring_unbuildable` is shared with [PolygonDropReason] because it is the same
 * condition; the `ev` says which phase refused the region.
 */
internal enum class PolygonNotRankedReason(val wire: String, val detail: String) {
    RUNTIME_UNSUPPORTED("runtime_unsupported", "polygon monitoring is not enabled in this build"),
    RING_UNBUILDABLE("ring_unbuildable", "the cached ring no longer validates")
}

/**
 * Why a location update could not settle a polygon containment question on its own.
 *
 * `no_usable_fix` is iOS's token on the same `ev`; `fix_too_old` is ours, reserved but not yet
 * emitted there.
 */
internal enum class PolygonFixRejection(val wire: String, val detail: String) {
    NO_USABLE_FIX("no_usable_fix", "it carries no usable accuracy or monotonic timestamp"),
    FIX_TOO_OLD("fix_too_old", "it predates the current evaluation session or is too old")
}

/**
 * Why a delivered OS geofence callback was discarded before anything was evaluated. Each nets
 * against an `os.callback.received` receipt.
 */
internal enum class PolygonCallbackDrop(val wire: String, val detail: String) {
    NOT_ROUTABLE("not_routable", "the fence is not a current routable registration"),
    DUPLICATE_DELIVERY("duplicate_delivery", "this fix was already delivered for this fence"),
    NO_POLYGON_IN_RANGE("no_polygon_in_range", "no routable polygon lies within reach of this fix"),
    NOT_CURRENT_SESSION("not_current_session", "it belongs to a user or state generation that is no longer current")
}

/** Why a held arrival was discarded without being reported. */
internal enum class PolygonArrivalExpiry(val wire: String, val detail: String) {
    WINDOW_ELAPSED("window_elapsed", "it went stale before any further fix could speak to it"),
    SESSION_ENDED("session_ended", "the evaluation session ended while it was still waiting"),
    EVIDENCE_BROKEN("evidence_broken", "a later fix positively placed the device outside the polygon")
}

/**
 * Why a marginal arrival was reported without a second fix agreeing with it.
 *
 * Only a fix that positively reads outside blocks a held arrival: refusing one loses the visit,
 * while a spurious one is corrected by the next decisive fix. The reason rides on the verdict so a
 * capture never reads it as decisive. [NOT_INDEPENDENT] matches iOS; iOS folds [UNJUDGEABLE] into
 * `accuracy_too_low`.
 */
internal enum class PolygonArrivalCommit(val wire: String, val detail: String) {
    NOT_INDEPENDENT(
        "corroboration_not_independent",
        "the next fix repeated the position already counted, so it is the same observation again"
    ),
    UNJUDGEABLE(
        "corroboration_unjudgeable",
        "the next fix could not separate inside from outside either, which is not evidence against it"
    )
}

/** Why an undecided verdict was left to stand without a precise fix. */
internal enum class PolygonFreshFixSkip(val wire: String, val detail: String) {
    WITHIN_COOLDOWN("within_cooldown", "one was already requested too recently to ask again"),
    NONE_ARRIVED("none_arrived", "nothing arrived inside the broadcast's budget"),
    UNCHANGED_POSITION(
        "unchanged_position",
        "a precise fix from this position already failed to decide this polygon"
    )
}

/** Why a passively delivered fix was not used. */
internal enum class PolygonPassiveSkip(val wire: String, val detail: String) {
    NOTHING_REGISTERED("nothing_registered", "no polygon is registered, so the delivery is stale")
}

/** Why a periodic re-check wake did nothing. */
internal enum class PolygonRecheckSkip(val wire: String, val detail: String) {
    NOTHING_REGISTERED("nothing_registered", "no polygon is registered, so the work retired itself"),
    NO_FIX("no_fix", "no location arrived within the re-check's budget"),
    NO_PERMISSION("no_permission", "location permission is not granted, so nothing was asked for")
}

/** A stop that was declined. The session stays live, so these are not endings. */
internal enum class PolygonApproachStopRefusal(val wire: String, val detail: String) {
    NOT_CURRENT_SESSION("not_current_session", "the live session belongs to a later generation"),
    DEADLINE_MISMATCH("deadline_mismatch", "it named a different session deadline")
}

/**
 * Why the bounded approach session discarded one of its own samples, or stopped.
 * [NOT_CURRENT_SESSION] ends sampling; the rest discard a single fix and keep going.
 */
internal enum class PolygonSamplingSkip(val wire: String, val detail: String) {
    NOT_CURRENT_SESSION("not_current_session", "it belongs to a user or state generation that is no longer current"),
    EMPTY_BATCH("empty_batch", "the delivery carried no locations"),
    NO_USABLE_FIX("no_usable_fix", "the sample carries no usable accuracy or monotonic timestamp"),
    NO_POLYGON_IN_RANGE("no_polygon_in_range", "no routable polygon lies within reach of the sample")
}

/**
 * Why a fix reached the engine but no polygon was evaluated against it.
 *
 * Not `os.callback.dropped`: the approach session reaches the engine too, so these have no
 * callback receipt to net against and would over-count dropped callbacks.
 */
internal enum class PolygonEvaluationSkip(val wire: String, val detail: String) {
    USER_STATE_CHANGED("user_state_changed", "user state changed while the fix was in flight"),
    OUTBOX_BLOCKED("outbox_blocked", "an older transition still cannot reach the durable queue"),
    NO_EVALUABLE_FENCES("no_evaluable_fences", "no active polygon had a usable ring to evaluate")
}

/**
 * Structured logger for geofence operations, tagged for logcat filtering.
 *
 * Every record is prose plus a ` || key=value` tail: `ev=` is the stable machine key (prose may be
 * reworded, `ev` may not) and `io=` classifies the record for replay. Only the tail is gated by
 * [GeofenceDiagnostics], except for records gated whole. Lines `docs/manual-tests` greps for must
 * not change; some are pinned by `documentedProse_expectExactStringsManualTestsGrepFor`.
 *
 * Most `why=` tokens are derived from the `reason` prose via [GeofenceLogTail.token] and pinned by
 * `GeofenceLogTailTest`, so a reword fails loudly. The `Polygon*` enums carry fixed tokens instead.
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
                tail("registration.added", GeofenceLogIo.OBSERVATION, listOf("nadd" to int(count))),
            tag = TAG
        )
    }

    /**
     * Which regions the OS is monitoring, by id, so a missed crossing can be checked against what
     * was actually registered.
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("nkeep" to int(count), "why" to "unchanged")
                ),
            tag = TAG
        )
    }

    fun logGeofencesRemoved(count: Int) {
        logger.debug(
            "Removed $count geofences from OS" +
                tail("registration.removed", GeofenceLogIo.OBSERVATION, listOf("nrem" to int(count))),
            tag = TAG
        )
    }

    /** The OS call underneath a reset; an observation, `module.reset` is the graded decision. */
    fun logGeofencesCleared() {
        logger.debug(
            "Cleared all geofences from OS" +
                tail("registration.cleared", GeofenceLogIo.OBSERVATION, listOf("why" to "cleared")),
            tag = TAG
        )
    }

    fun logRegistrationFailed(message: String?) {
        logger.error(
            "Failed to register geofences: $message" +
                tail(
                    "registration.failed",
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "why" to token(message ?: "mapping_failed"))
                ),
            tag = TAG
        )
    }

    /**
     * The N-of-M selection, so a fence ranked past the cap is distinguishable from one that was
     * registered and never fired. The lists are lambdas so their per-region cost is paid only
     * when diagnostics are on.
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
                GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    GeofenceLogTail.position(latitude, longitude).map { (k, v) ->
                        (if (k == "lat") "rlat" else if (k == "lon") "rlon" else k) to v
                    } + listOf("rad" to num(radiusMeters, 0))
                ),
            tag = TAG
        )
    }

    // MARK: - Permissions and lifecycle

    /**
     * A registration refused because the tier is not granted. `perm` carries the tier, like every
     * other writer of this key; the permission constant goes in `ctx`.
     */
    fun logMissingPermission(permission: String) {
        logger.error(
            "Cannot register geofences: $permission not granted. Host app must request this permission." +
                tail(
                    "permission.changed",
                    GeofenceLogIo.INPUT,
                    listOf("perm" to PERMISSION_DENIED, "why" to "not_granted", "ctx" to token(permission))
                ),
            tag = TAG
        )
    }

    /**
     * The granted location tier, reported at process start and on foreground entry when it changes.
     * `perm` uses iOS's vocabulary so one parser reads both platforms, and `ok` means background
     * delivery is available (Always), matching iOS.
     */
    fun logPermissionTier(tier: String) {
        logger.info(
            "Location permission is now $tier" +
                tail(
                    "permission.changed",
                    GeofenceLogIo.INPUT,
                    listOf("perm" to token(tier), "ok" to bool(tier == PERMISSION_ALWAYS))
                ),
            tag = TAG
        )
    }

    fun logBackgroundDeliveryUnavailable(reason: String) {
        logger.info(
            "Geofence sync ($reason): ACCESS_BACKGROUND_LOCATION not granted — transitions will only fire while the app is in the foreground" +
                tail(
                    "permission.changed",
                    GeofenceLogIo.INPUT,
                    listOf("perm" to "when_in_use", "why" to "foreground_only", "ctx" to token(reason))
                ),
            tag = TAG
        )
    }

    /**
     * The module came up. A cold wake is announced separately via [logModuleWoke] because the OS,
     * not the SDK, decides the order of the two.
     */
    fun logModuleInitialized(launchReason: GeofenceLaunchReason) {
        logger.info(
            "Geofence module initialized (${launchReason.wire})" +
                tail("module.init", GeofenceLogIo.INPUT, listOf("launch" to launchReason.wire)),
            tag = TAG
        )
    }

    /** The process was started by something other than the app, e.g. a boot restore. */
    fun logModuleWoke(launchReason: GeofenceLaunchReason) {
        logger.info(
            "Geofence module woken (${launchReason.wire})" +
                tail("module.wake", GeofenceLogIo.INPUT, listOf("launch" to launchReason.wire)),
            tag = TAG
        )
    }

    fun logMissingLocationModule() {
        logger.error(
            "ModuleGeofence requires ModuleLocation to be registered alongside it. Add ModuleLocation to CustomerIOConfigBuilder; geofencing will not function until then." +
                tail(
                    "module.init",
                    GeofenceLogIo.INPUT,
                    listOf("ok" to bool(false), "why" to "missing_location_module")
                ),
            tag = TAG
        )
    }

    /** Sign-out observed. `ev=info`, not `module.reset`: that key means the outcome, not the intent. */
    fun logGeofenceStateResetOnSignOut() {
        logger.debug(
            "Geofence state reset on user sign-out: clearing persisted regions and OS registrations" +
                tail("info", GeofenceLogIo.OBSERVATION, listOf("why" to "sign_out_reset_started")),
            tag = TAG
        )
    }

    /** Reset completed: the same key iOS asserts for "logout clears geofences". */
    fun logResetCompleted() {
        logger.debug(
            "Reset completed: monitoring stopped and user-scoped state cleared" +
                tail("module.reset", GeofenceLogIo.OUTPUT, listOf("ok" to bool(true))),
            tag = TAG
        )
    }

    /** A reset whose OS clear failed; state is kept so the next refresh retries. */
    fun logResetFailed(reason: String) {
        logger.debug(
            "Reset failed: OS clear did not complete, keeping state for the next refresh to retry ($reason)" +
                tail(
                    "module.reset",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "why" to "os_clear_failed")
                ),
            tag = TAG
        )
    }

    /** A reset that did not clear because another user is signed in. */
    fun logResetSuperseded() {
        logger.debug(
            "Reset skipped: another user is signed in" +
                tail(
                    "module.reset",
                    GeofenceLogIo.OUTPUT,
                    listOf("ok" to bool(false), "why" to "other_user_signed_in")
                ),
            tag = TAG
        )
    }

    /**
     * A user signed in or out. Logged per identify that names a user and per reset; the
     * UserChangedEvent(null) after a reset is not logged. No identifier is written.
     */
    fun logIdentityChanged(identified: Boolean) {
        logger.debug(
            "Geofence identity changed: ${if (identified) "user signed in" else "user signed out"}" +
                tail("identity.changed", GeofenceLogIo.INPUT, listOf("ok" to bool(identified))),
            tag = TAG
        )
    }

    // MARK: - OS callbacks

    /**
     * A crossing as the OS reported it, with the fix the OS computed for it. Logged at the receiver,
     * before routing and before the `Location` is narrowed to lat/lng.
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

    /**
     * A position arrived from the Location module. Arrivals only, never the SDK's own reads.
     * No `acc`/`age`: the core event carries coordinates only.
     */
    fun logLocationFix(latitude: Double, longitude: Double) {
        logger.debug(
            "Location fix delivered to geofencing" +
                tail(
                    "location.fix",
                    GeofenceLogIo.INPUT,
                    GeofenceLogTail.position(latitude, longitude) + listOf("prov" to "bus")
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

    /**
     * A human-readable note outside the asserted vocabulary (`ev=info`, `io=obs`); scenarios never
     * assert on it. Not `os.callback.dropped`: an unreadable broadcast has no `os.callback.received`
     * receipt to net against, and would inflate the dropped count.
     */
    fun logInfo(reason: String, fields: List<Pair<String, String?>> = emptyList()) {
        logger.debug(
            "Geofence note: ${reason.replace('_', ' ')}" +
                tail("info", GeofenceLogIo.OBSERVATION, listOf("why" to reason) + fields),
            tag = TAG
        )
    }

    /** Called per fence, so [geofenceId] keeps a multi-fence DWELL from logging identical records. */
    fun logUnknownTransition(geofenceId: String, transitionType: Int) {
        logger.debug(
            "Ignoring geofence transition type=$transitionType for '$geofenceId' (only ENTER and EXIT are tracked)" +
                tail(
                    "os.callback.dropped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "gms" to int(transitionType), "why" to "unsupported_transition_type")
                ),
            tag = TAG
        )
    }

    fun logMovementTriggerIgnoredNonExit(transitionName: String) {
        logger.debug(
            "Movement trigger geofence fired with transition=$transitionName; only EXIT triggers a sync" +
                tail(
                    "os.callback.dropped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("t" to token(transitionName), "why" to "movement_trigger_not_exit")
                ),
            tag = TAG
        )
    }

    fun logReceiverSkipped(reason: String) {
        logger.debug(
            "Geofence receiver skipped: $reason" +
                tail("os.callback.dropped", GeofenceLogIo.OBSERVATION, listOf("why" to token(reason))),
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
     * The SDK judged this crossing real and persisted it; the record replay asserts on (name matches
     * iOS). Call only once the pending rows are on disk. What happens next is `delivery.*`.
     *
     * The position is on the broadcast's `os.callback.received` record, whose `ids` list names this
     * crossing. [rows] is the per-geoset fan-out, in the tail as `n` only: the prose is pinned.
     */
    fun logTransitionAccepted(geofenceId: String, transitionName: String, rows: Int) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: queued for at-least-once delivery (WorkManager now, analytics pipeline on next foreground)" +
                tail(
                    "transition.accepted",
                    GeofenceLogIo.OUTPUT,
                    listOf("id" to geofenceId, "t" to token(transitionName), "n" to int(rows))
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "no_identified_user")
                ),
            tag = TAG
        )
    }

    // MARK: - Sync

    /**
     * Time in ms to resolve the crossing pipeline for one broadcast. On a cold process this builds
     * the geofence graph (GMS client included) inside the broadcast budget; expect a large first
     * value per process and about zero afterwards. Gated whole: it fires on every broadcast and is
     * meaningless without its tail.
     */
    fun logDispatchReady(elapsedMs: Long) {
        if (!GeofenceDiagnostics.isEnabled) return
        logger.debug(
            "Crossing pipeline ready after ${elapsedMs}ms" +
                tail("dispatch.ready", GeofenceLogIo.OBSERVATION, listOf("ms" to int(elapsedMs.toInt()))),
            tag = TAG
        )
    }

    fun logSyncTriggered(reason: String) {
        logger.debug(
            "Geofence sync triggered: $reason" +
                tail("sync.triggered", GeofenceLogIo.OBSERVATION, listOf("why" to token(reason))),
            tag = TAG
        )
    }

    fun logSyncSkipped(reason: String) {
        logger.debug(
            "Geofence sync skipped: $reason" +
                tail("sync.skipped", GeofenceLogIo.OBSERVATION, listOf("why" to token(reason))),
            tag = TAG
        )
    }

    fun logSyncSkippedNoLocation(reason: String) {
        logger.debug(
            "Geofence sync skipped ($reason): no location available" +
                tail(
                    "sync.skipped",
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to "no_permission", "ctx" to token(reason))
                ),
            tag = TAG
        )
    }

    fun logSyncSkippedFresh() {
        logger.debug(
            "Geofence sync skipped: last successful sync is still within the freshness window" +
                tail("sync.skipped", GeofenceLogIo.OBSERVATION, listOf("why" to "within_freshness_window")),
            tag = TAG
        )
    }

    fun logSyncFailed(message: String?) {
        logger.error(
            "Geofence sync failed: $message" +
                tail(
                    "sync.failed",
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "n" to int(count),
                        "mvmt" to bool(movementTriggerRegistered),
                        "ms" to elapsedMillis?.toString()
                    )
                ),
            tag = TAG
        )
    }

    /**
     * Outcome of a nearby-geofence fetch; an input, since replay feeds the response back. [catalog]
     * is a lambda because building it decodes every polygon ring again, wanted only with diagnostics on.
     */
    fun logApiFetchResult(
        returnedCount: Int,
        elapsedMillis: Long?,
        catalog: () -> List<GeofenceCatalogEntry> = { emptyList() }
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
        logFenceCatalog(catalog)
    }

    /**
     * One record per fetched fence, as the server sent it before validation, so a dropped record
     * still gets a row. Lets a replay place fences as they were at capture time, since workspace
     * geometry changes later. Gated whole: the prose alone is worthless.
     */
    private fun logFenceCatalog(catalog: () -> List<GeofenceCatalogEntry>) {
        if (!GeofenceDiagnostics.isEnabled) return
        val entries = catalog()
        if (entries.isEmpty()) return
        for (entry in entries) {
            logger.debug(
                "Geofence '${entry.id}' catalogued" +
                    tail(
                        "fence.cataloged",
                        GeofenceLogIo.INPUT,
                        listOf(
                            "id" to entry.id,
                            "name" to entry.name,
                            "gs" to composedList(entry.geosetIds),
                            // Without it a replay would treat a polygon's wake circle as the fence.
                            "sh" to entry.shape,
                            "lat" to num(entry.latitude, 5),
                            "lon" to num(entry.longitude, 5),
                            // Omitted, never substituted, when the record has no radius.
                            "rad" to num(entry.radiusMeters, 0),
                            "nv" to int(entry.vertices?.size),
                            "ring" to entry.vertices?.let(::ringPairs)?.let(::composedList),
                            "tt" to composedList(entry.transitionTypes)
                        )
                    ),
                tag = TAG
            )
        }
    }

    /**
     * Outer ring as `lat_lon` pairs (SDK order, not GeoJSON's). Capped like any list, so a consumer
     * finding fewer pairs than `nv` must reject the ring: a truncated ring still looks valid.
     */
    private fun ringPairs(vertices: List<PolygonCoordinate>): List<String> =
        vertices.mapNotNull { vertex ->
            val lat = num(vertex.latitude, 5) ?: return@mapNotNull null
            val lon = num(vertex.longitude, 5) ?: return@mapNotNull null
            "${lat}_$lon"
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("ok" to bool(false), "why" to "unknown_transition_type", "value" to token(value))
                ),
            tag = TAG
        )
    }

    fun logMovementRearmedAfterFailedRefresh() {
        logger.debug(
            "Movement refresh failed; re-ranking from cache to re-arm the movement trigger" +
                tail("movement.rearmed", GeofenceLogIo.OBSERVATION, listOf("why" to "refresh_failed")),
            tag = TAG
        )
    }

    // MARK: - Storage

    /**
     * What survived a cold start, i.e. whether a background wake had anything to work from. Gated
     * whole, and [regionCount] is a lambda, so the cached list is only deserialized with diagnostics on.
     */
    fun logStorageLoaded(regionCount: () -> Int, hasAnchor: Boolean) {
        if (!GeofenceDiagnostics.isEnabled) return
        val count = regionCount()
        logger.debug(
            "Loaded $count cached region(s) from storage" +
                tail(
                    "storage.loaded",
                    GeofenceLogIo.OBSERVATION,
                    listOf("n" to int(count), "anchor" to bool(hasAnchor))
                ),
            tag = TAG
        )
    }

    fun logPersistFailed(geofenceId: String, transitionName: String) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: file outbox unavailable — kept durable staging for recovery" +
                tail(
                    "storage.write.failed",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "t" to token(transitionName), "ok" to bool(false))
                ),
            tag = TAG
        )
    }

    // MARK: - Delivery
    //
    // Classified `obs` and namespaced under `delivery.` so replay can drop the whole family: replay
    // checks that the SDK accepted a transition, not that it reached the backend.

    fun logEventDeliveryRetryable(geofenceId: String, transitionName: String, message: String?) {
        logger.debug(
            "Geofence '$geofenceId' $transitionName: HTTP delivery hit network error ($message); WorkManager will retry" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OBSERVATION,
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

    fun logEventDeliveryFailed(
        geofenceId: String,
        transitionName: String,
        message: String?,
        willRetry: Boolean
    ) {
        val disposal = if (willRetry) "retained at the head of the ordered outbox" else "dropped, refusal is permanent"
        logger.error(
            "Geofence '$geofenceId' $transitionName: HTTP delivery failed; $disposal — $message" +
                tail(
                    "delivery.failed",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(false),
                        "retry" to bool(willRetry),
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "t" to token(transitionName), "why" to "already_delivered")
                ),
            tag = TAG
        )
    }

    fun logEventWorkerEntryMissing() {
        logger.debug(
            "Geofence event worker woke with nothing to send: an earlier node in the delivery chain drained the queue, or the foreground flush did" +
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to "queue_empty")
                ),
            tag = TAG
        )
    }

    /**
     * Distinct from [logEventWorkerEntryMissing], which names a queue that really was empty: one
     * shared record would report a drain for a file we never read. Token matches iOS.
     */
    fun logEventWorkerQueueUnreadable(attempt: Int, willRetry: Boolean) {
        logger.error(
            "Geofence event worker could not read the pending queue; leaving it untouched and " +
                (if (willRetry) "retrying on a backoff" else "giving up this wake — the next transition or foreground flush retries") +
                tail(
                    "queue.unreadable",
                    GeofenceLogIo.INPUT,
                    listOf("why" to "read_failed", "via" to "work_manager", "n" to int(attempt), "retry" to bool(willRetry))
                ),
            tag = TAG
        )
    }

    /** Same failure as [logEventWorkerQueueUnreadable], with no attempt or retry to report. */
    fun logForegroundFlushQueueUnreadable() {
        logger.error(
            "Foreground flush could not read the pending queue; leaving it untouched and retrying on the next foreground" +
                tail(
                    "queue.unreadable",
                    GeofenceLogIo.INPUT,
                    listOf("why" to "read_failed", "via" to "foreground_flush")
                ),
            tag = TAG
        )
    }

    fun logForegroundFlushSnapshot(count: Int) {
        logger.debug(
            "Geofence foreground flush: $count pending transition(s) to hand off to the analytics pipeline" +
                tail(
                    "delivery.flush",
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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
                    GeofenceLogIo.OBSERVATION,
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

    fun logContainmentJudged(insideCount: Int) {
        logger.debug(
            "Judged containment from the live fix: inside $insideCount region(s)" +
                tail(
                    "containment.judged",
                    GeofenceLogIo.OBSERVATION,
                    listOf("n" to int(insideCount))
                ),
            tag = TAG
        )
    }

    fun logEventDeliveredButNotRemoved(geofenceId: String, transitionName: String) {
        logger.error(
            "Geofence '$geofenceId' $transitionName: delivered, but removing it from the pending store failed, so it is still queued. Retrying on a backoff; the backend deduplicates the repeat send on transitionId." +
                // ok=true: the send reached the backend, only cleanup failed. Delivery counts must
                // skip why=not_removed, since the retry logs its own delivery.sent.
                tail(
                    "delivery.sent",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "t" to token(transitionName),
                        "ok" to bool(true),
                        "retry" to bool(true),
                        "why" to "not_removed"
                    )
                ),
            tag = TAG
        )
    }

    fun logGmsCallTimedOut(description: String) {
        logger.error(
            "GMS $description gave no callback before the deadline — treating as failed; Play Services may be updating or unresponsive" +
                // INPUT, not OUTPUT: the silence is the environment's, and the SDK only reacts to it.
                tail(
                    "os.error",
                    GeofenceLogIo.INPUT,
                    listOf("ok" to bool(false), "op" to token(description), "why" to "timeout")
                ),
            tag = TAG
        )
    }

    fun logPolygonDropped(geofenceId: String, reason: PolygonDropReason) {
        logger.error(
            "Geofence '$geofenceId' dropped — polygon rejected: ${reason.detail}" +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "sh" to "polygon", "why" to reason.wire)
                ),
            tag = TAG
        )
    }

    fun logPolygonDroppedUnsupportedRuntime(geofenceId: String) {
        logger.info(
            "Geofence '$geofenceId' dropped — it is a polygon, and this SDK build monitors circles only. The record was decoded and validated but never registered; its enclosing circle is a proximity trigger, not the fence, so registering it would report transitions for the wrong area." +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "sh" to "polygon", "why" to "runtime_unsupported")
                ),
            tag = TAG
        )
    }

    fun logPinnedRegionDroppedAtOsLimit(geofenceId: String, availableSlots: Int) {
        logger.error(
            "Geofence '$geofenceId' was pinned for exit monitoring but dropped — more regions are pinned than the $availableSlots business slots Google Play services allows. Keeping it would make the OS reject every geofence in the batch, so the farthest pinned regions are released first; their exits may be missed." +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "n" to int(availableSlots), "why" to "os_slot_limit")
                ),
            tag = TAG
        )
    }

    /**
     * An arrival decided but held for a second agreeing fix. Its own record, not a drop reason: the
     * fence is arriving, and without it a hold looks like a callback that never came.
     */
    fun logPolygonArrivalPending(
        geofenceId: String,
        signedBoundaryDistanceMeters: Double?,
        horizontalAccuracyMeters: Double?,
        fixAgeSeconds: Double?
    ) {
        logger.debug(
            "Polygon '$geofenceId' reads as an arrival but its accuracy circle reaches the boundary. Holding for a second agreeing fix." +
                tail(
                    "polygon.arrival.pending",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "sh" to "polygon",
                        "edge" to num(signedBoundaryDistanceMeters),
                        "acc" to num(horizontalAccuracyMeters),
                        "age" to num(fixAgeSeconds)
                    )
                ),
            tag = TAG
        )
    }

    /**
     * One record per discarded approach sample. Sampling supplies corroborating fixes, so a silent
     * gap here would look like the session never ran.
     */
    fun logPolygonSamplingSkipped(reason: PolygonSamplingSkip) {
        logger.debug(
            "Polygon approach sample skipped — ${reason.detail}." +
                tail(
                    "polygon.sampling.skipped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to reason.wire)
                ),
            tag = TAG
        )
    }

    /**
     * A fix the engine accepted but evaluated nothing against. Not a callback drop, because the
     * approach session reaches this path too; see [PolygonEvaluationSkip].
     */
    fun logPolygonEvaluationSkipped(reason: PolygonEvaluationSkip) {
        logger.debug(
            "Polygon evaluation skipped — ${reason.detail}. No polygon was judged against this fix." +
                tail(
                    "polygon.evaluation.skipped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to reason.wire)
                ),
            tag = TAG
        )
    }

    /**
     * A held arrival discarded without being reported. Counterpart to [logPolygonArrivalPending],
     * so a capture can tell which holds completed and which died.
     */
    fun logPolygonArrivalExpired(
        geofenceId: String,
        reason: PolygonArrivalExpiry,
        heldForSeconds: Double? = null
    ) {
        logger.debug(
            "Polygon '$geofenceId' arrival expired — ${reason.detail}. The visit was not reported." +
                tail(
                    "polygon.arrival.expired",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "sh" to "polygon",
                        "why" to reason.wire,
                        "held" to num(heldForSeconds)
                    )
                ),
            tag = TAG
        )
    }

    /**
     * A precise fix asked for because the delivered one decided nothing. Followed by exactly one of
     * [logPolygonFreshFixReceived] or [logPolygonFreshFixSkipped], so an unanswered request is
     * distinguishable from one never made.
     */
    fun logPolygonFreshFixRequested(geofenceIds: List<String>) {
        logger.debug(
            "Requesting a precise fix: ${geofenceIds.size} polygon(s) could not be decided from the delivered one." +
                tail(
                    "polygon.freshfix.requested",
                    GeofenceLogIo.OBSERVATION,
                    listOf("ids" to list(geofenceIds), "n" to int(geofenceIds.size))
                ),
            tag = TAG
        )
    }

    fun logPolygonFreshFixReceived(location: Location, waitedSeconds: Double) {
        logger.debug(
            "Precise fix received; evaluating the callback against it." +
                tail(
                    "polygon.freshfix.received",
                    GeofenceLogIo.INPUT,
                    listOf("waited" to num(waitedSeconds)) +
                        GeofenceLogTail.fixQuality(location, GeofenceLogTail.FixSource.FRESH_REQUEST) +
                        GeofenceLogTail.position(location)
                ),
            tag = TAG
        )
    }

    /**
     * [candidateCount] is every registered polygon and [admittedIds] those whose wake circle holds
     * the fix. Logged even when nothing is admitted, so "ran, admitted nothing" is distinguishable
     * from "not scheduled".
     */
    fun logPolygonRecheckRan(
        location: Location,
        candidateCount: Int,
        admittedIds: List<String>,
        clearedIds: List<String>
    ) {
        logger.debug(
            "Periodic polygon re-check: ${admittedIds.size} of $candidateCount registered polygon(s) are within their wake circle, " +
                "${clearedIds.size} left." +
                tail(
                    "polygon.recheck.ran",
                    GeofenceLogIo.INPUT,
                    listOf(
                        "cand" to int(candidateCount),
                        "n" to int(admittedIds.size),
                        "ids" to list(admittedIds),
                        "ncleared" to int(clearedIds.size),
                        "cleared" to list(clearedIds)
                    ) + GeofenceLogTail.fixQuality(location, GeofenceLogTail.FixSource.FRESH_REQUEST) +
                        GeofenceLogTail.position(location)
                ),
            tag = TAG
        )
    }

    /**
     * The passive listener is registered. A start with no [logPolygonPassiveReceived] means no other
     * app asked for location meanwhile, which is not a fault.
     */
    fun logPolygonPassiveStarted() {
        logger.debug(
            "Listening for location other apps request; no sensor is turned on for this." +
                tail("polygon.passive.started", GeofenceLogIo.OBSERVATION, emptyList()),
            tag = TAG
        )
    }

    fun logPolygonPassiveStopped() {
        logger.debug(
            "Stopped listening for other apps' location." +
                tail("polygon.passive.stopped", GeofenceLogIo.OBSERVATION, emptyList()),
            tag = TAG
        )
    }

    fun logPolygonPassiveReceived(
        location: Location,
        candidateCount: Int,
        admittedIds: List<String>,
        clearedIds: List<String>
    ) {
        logger.debug(
            "A fix arrived from another app's request: ${admittedIds.size} of $candidateCount registered polygon(s) " +
                "are within their wake circle, ${clearedIds.size} left." +
                tail(
                    "polygon.passive.received",
                    GeofenceLogIo.INPUT,
                    listOf(
                        "cand" to int(candidateCount),
                        "n" to int(admittedIds.size),
                        "ids" to list(admittedIds),
                        "ncleared" to int(clearedIds.size),
                        "cleared" to list(clearedIds)
                    ) + GeofenceLogTail.fixQuality(location, GeofenceLogTail.FixSource.CACHED) +
                        GeofenceLogTail.position(location)
                ),
            tag = TAG
        )
    }

    fun logPolygonPassiveSkipped(reason: PolygonPassiveSkip) {
        logger.debug(
            "Passive fix not used — ${reason.detail}." +
                tail("polygon.passive.skipped", GeofenceLogIo.OBSERVATION, listOf("why" to reason.wire)),
            tag = TAG
        )
    }

    fun logPolygonPassiveFailed(message: String?) {
        logger.debug(
            "Passive location listening failed — ${message ?: "no reason given"}." +
                tail("polygon.passive.failed", GeofenceLogIo.OBSERVATION, listOf("why" to token(message ?: "unknown"))),
            tag = TAG
        )
    }

    fun logPolygonRecheckSkipped(reason: PolygonRecheckSkip) {
        logger.debug(
            "Periodic polygon re-check did nothing — ${reason.detail}." +
                tail(
                    "polygon.recheck.skipped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to reason.wire)
                ),
            tag = TAG
        )
    }

    fun logPolygonFreshFixSkipped(reason: PolygonFreshFixSkip) {
        logger.debug(
            "No precise fix for this verdict — ${reason.detail}. The delivered fix stands." +
                tail(
                    "polygon.freshfix.skipped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to reason.wire)
                ),
            tag = TAG
        )
    }

    /** One record per discarded callback; nets against its `os.callback.received` receipt. */
    fun logPolygonCallbackDropped(reason: PolygonCallbackDrop, geofenceId: String? = null) {
        logger.debug(
            "Polygon callback discarded — ${reason.detail}. Nothing was evaluated for it." +
                tail(
                    "os.callback.dropped",
                    // `obs`, like every other writer of this key: replay routes on `io`, so the key
                    // must not mix classifications.
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "why" to reason.wire,
                        "id" to geofenceId
                    )
                ),
            tag = TAG
        )
    }

    fun logPolygonFixNotUsable(
        reason: PolygonFixRejection,
        horizontalAccuracyMeters: Double? = null,
        fixAgeSeconds: Double? = null
    ) {
        logger.debug(
            "Polygon fix ignored — ${reason.detail}. Responsive monitoring is best-effort: it decides only from fixes that are decisive on their own." +
                tail(
                    "polygon.undecided",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "why" to reason.wire,
                        "acc" to num(horizontalAccuracyMeters),
                        "age" to num(fixAgeSeconds)
                    )
                ),
            tag = TAG
        )
    }

    /**
     * A fix that arrived, was usable, and was evaluated, but could not separate inside from
     * outside. Without it a capture cannot tell an undecidable fix from one that never arrived.
     */
    fun logPolygonUndecided(
        geofenceId: String,
        reason: PolygonUndecidedReason,
        signedBoundaryDistanceMeters: Double?,
        horizontalAccuracyMeters: Double,
        fixAgeSeconds: Double
    ) {
        logger.debug(
            "Polygon '$geofenceId' undecided for this fix. The fix was evaluated but does not " +
                "separate inside from outside, so no transition is claimed." +
                tail(
                    "polygon.undecided",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "sh" to "polygon",
                        "why" to reason.wire,
                        "edge" to num(signedBoundaryDistanceMeters),
                        "acc" to num(horizontalAccuracyMeters),
                        "age" to num(fixAgeSeconds)
                    )
                ),
            tag = TAG
        )
    }

    /**
     * The evidence behind a transition the evaluator claimed; `polygon.undecided` covers refusals.
     *
     * Claimed, not delivered: written before the business processor's guards, and repeated on each
     * fix until something commits, so count these as verdicts, not events. `cor` describes this
     * fix, not the arrival: a completing fix clear of the ring decides alone and reports `cor=false`.
     */
    fun logPolygonDecided(
        geofenceId: String,
        transitionName: String,
        signedBoundaryDistanceMeters: Double?,
        horizontalAccuracyMeters: Double,
        fixAgeSeconds: Double,
        corroborated: Boolean,
        uncorroboratedReason: PolygonArrivalCommit? = null
    ) {
        val detail = uncorroboratedReason?.let { " Reported unconfirmed: ${it.detail}." } ?: ""
        logger.debug(
            "Polygon '$geofenceId' decided this fix is a $transitionName.$detail" +
                tail(
                    "polygon.decided",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "sh" to "polygon",
                        "t" to token(transitionName),
                        "edge" to num(signedBoundaryDistanceMeters),
                        "acc" to num(horizontalAccuracyMeters),
                        "age" to num(fixAgeSeconds),
                        "cor" to bool(corroborated),
                        "corwhy" to uncorroboratedReason?.wire
                    )
                ),
            tag = TAG
        )
    }

    /**
     * A decisive fix that agreed with the committed state: the ordinary good fix margins are
     * calibrated against. Bounded by the sampling session, so a handful of rows per wake.
     */
    fun logPolygonUnchanged(
        geofenceId: String,
        membership: String,
        signedBoundaryDistanceMeters: Double?,
        horizontalAccuracyMeters: Double,
        fixAgeSeconds: Double
    ) {
        logger.debug(
            "Polygon '$geofenceId' agrees with the committed state; no transition." +
                tail(
                    "polygon.unchanged",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "id" to geofenceId,
                        "sh" to "polygon",
                        "m" to token(membership),
                        "edge" to num(signedBoundaryDistanceMeters),
                        "acc" to num(horizontalAccuracyMeters),
                        "age" to num(fixAgeSeconds)
                    )
                ),
            tag = TAG
        )
    }

    fun logPolygonRegionNotRanked(geofenceId: String, reason: PolygonNotRankedReason) {
        logger.debug(
            "Geofence '$geofenceId' excluded from ranking — ${reason.detail}. It stays cached and is never registered as its enclosing circle." +
                tail(
                    "rank.excluded",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "sh" to "polygon", "why" to reason.wire)
                ),
            tag = TAG
        )
    }

    fun logTransitionDroppedRetiredId(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' transition dropped — backend removed it and OS cleanup is pending" +
                // Distinct from `unknown_id`: we still hold this fence's last known definition, so
                // the drop is deliberate retirement rather than a fence we cannot account for.
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "why" to "retired_id")
                ),
            tag = TAG
        )
    }

    fun logTransitionDroppedUnarmedId(geofenceId: String) {
        logger.debug(
            "Geofence '$geofenceId' transition dropped — registered but routing is not armed for this session; OS registration kept" +
                tail(
                    "transition.dropped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "why" to "routing_unarmed")
                ),
            tag = TAG
        )
    }

    fun logUnsupportedGeometryDropped(geofenceId: String, type: String) {
        logger.error(
            "Geofence '$geofenceId' dropped — unsupported geometry type='$type' (only Polygon is understood). Not degraded to a circle; check SDK / backend version alignment." +
                tail(
                    "registration.rejected",
                    GeofenceLogIo.OBSERVATION,
                    listOf("id" to geofenceId, "sh" to type, "why" to "unknown_shape")
                ),
            tag = TAG
        )
    }

    fun logPolygonApproachMonitoringStarted() {
        logger.debug(
            "Polygon responsive approach monitoring registered" +
                tail("polygon.approach.started", GeofenceLogIo.OBSERVATION),
            tag = TAG
        )
    }

    /**
     * The sampler discarded its own request because the session went stale before it was granted.
     * Not `polygon.approach.stopped`: the removal it triggers logs that ending itself. `gen` shows
     * whether a newer session is still live.
     */
    fun logPolygonApproachRequestDiscarded(userStateGeneration: Long?) {
        logger.debug(
            "Polygon approach request discarded — it went stale between being asked for and granted." +
                tail(
                    "polygon.approach.request_discarded",
                    GeofenceLogIo.OBSERVATION,
                    listOf("gen" to userStateGeneration?.let { int(it.toInt()) })
                ),
            tag = TAG
        )
    }

    /**
     * A stop that was declined, leaving sampling running. Separate from
     * [logPolygonApproachMonitoringStopped] so these are not counted as endings.
     */
    fun logPolygonApproachStopRefused(reason: PolygonApproachStopRefusal) {
        logger.debug(
            "Polygon approach stop refused — ${reason.detail}. Sampling continues." +
                tail(
                    "polygon.approach.stop_refused",
                    GeofenceLogIo.OBSERVATION,
                    listOf("why" to reason.wire)
                ),
            tag = TAG
        )
    }

    /** [samplesReceived] is how many sample batches the session delivered, zero being the finding. */
    fun logPolygonApproachMonitoringStopped(samplesReceived: Int? = null) {
        logger.debug(
            "Polygon responsive approach monitoring removed" +
                tail(
                    "polygon.approach.stopped",
                    GeofenceLogIo.OBSERVATION,
                    listOf("n" to samplesReceived?.let(::int))
                ),
            tag = TAG
        )
    }

    /**
     * The OS refused to start or stop the location stream, so this is the environment's fault and a
     * replay feeds it back rather than comparing it. [operation] separates the two call sites.
     */
    fun logPolygonApproachRequestFailed(message: String?, operation: String) {
        logger.error(
            "Polygon responsive approach monitoring unavailable: $message" +
                tail(
                    "polygon.approach.failed",
                    GeofenceLogIo.INPUT,
                    listOf(
                        "ok" to bool(false),
                        "op" to operation,
                        "why" to token(message ?: "unknown")
                    )
                ),
            tag = TAG
        )
    }

    /**
     * Our own handling of a delivered batch threw, so its locations are lost. An observation, not an
     * input: the OS did its part. [operation] separates the broadcast from the worker.
     */
    fun logPolygonApproachProcessingFailed(message: String?, operation: String) {
        logger.error(
            "Polygon responsive approach locations dropped: $message" +
                tail(
                    "polygon.approach.dropped",
                    GeofenceLogIo.OBSERVATION,
                    listOf(
                        "ok" to bool(false),
                        "op" to operation,
                        "why" to token(message ?: "unknown")
                    )
                ),
            tag = TAG
        )
    }

    companion object {
        private const val TAG = "Geofence"

        /** iOS's vocabulary, so one parser reads both platforms. */
        const val PERMISSION_DENIED = "denied"
        const val PERMISSION_WHEN_IN_USE = "when_in_use"
        const val PERMISSION_ALWAYS = "always"
    }
}
