package io.customer.geofence

import android.location.Location
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
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
    private fun invocations(): List<Triple<String, List<String>, (GeofenceLogger) -> Unit>> {
        val fix = location()
        return listOf(
            Triple("geofencesRegistered", listOf("nadd")) { it.logGeofencesRegistered(19) },
            Triple("regionsRegisteredIds", listOf("n", "ids", "mvmt")) { it.logRegionsRegisteredIds(listOf("a", "b"), "cio_movement_trigger") },
            Triple("businessKept", listOf("nkeep", "why")) { it.logBusinessGeofencesKept(4) },
            Triple("geofencesRemoved", listOf("nrem")) { it.logGeofencesRemoved(2) },
            Triple("geofencesCleared", listOf("why")) { it.logGeofencesCleared() },
            Triple("registrationFailed", listOf("ok", "why")) { it.logRegistrationFailed("GMS unavailable") },
            Triple("removalFailed", listOf("ok", "op", "why")) { it.logRemovalFailed("GMS unavailable") },
            Triple("invalidRegionDropped", listOf("id", "why")) { it.logInvalidRegionDropped("notl core") },
            Triple("regionMappingFailed", listOf("id", "why")) { it.logRegionMappingFailed("notl_core", "bad radius") },
            Triple("rankEvaluated", listOf("ncand", "n", "ranked", "evicted")) { it.logRankEvaluated(30, 2, { listOf("a", "b") }, { listOf("c") }, { mapOf("a" to 120.0, "b" to 340.0) }) },
            Triple("movementTriggerRegistered", listOf("rad")) { it.logMovementTriggerRegistered(43.2, -79.0, 500.0) },
            Triple("missingPermission", listOf("perm", "why")) { it.logMissingPermission("ACCESS_FINE_LOCATION") },
            Triple("backgroundUnavailable", listOf("perm", "ctx")) { it.logBackgroundDeliveryUnavailable("app-launch") },
            Triple("moduleInitialized", listOf("launch")) { it.logModuleInitialized(GeofenceLaunchReason.APP_START) },
            Triple("moduleWoke", listOf("launch")) { it.logModuleWoke(GeofenceLaunchReason.BOOT_RESTORE) },
            Triple("missingLocationModule", listOf("ok", "why")) { it.logMissingLocationModule() },
            Triple("stateResetOnSignOut", listOf("why")) { it.logGeofenceStateResetOnSignOut() },
            Triple("callbackReceived", listOf("ids", "n", "t", "fixsrc", "acc", "age", "sim")) { it.logCallbackReceived(listOf("notl_core"), "ENTER", fix, GeofenceLogTail.FixSource.OS_TRIGGER) },
            Triple("callbackReceivedNoFix", listOf("fixsrc")) { it.logCallbackReceived(listOf("notl_core"), "EXIT", null, GeofenceLogTail.FixSource.NONE) },
            Triple("transitionWithoutLocation", listOf("fixsrc", "why")) { it.logTransitionWithoutLocation() },
            Triple("unknownTransition", listOf("gms", "why")) { it.logUnknownTransition(4) },
            Triple("movementIgnoredNonExit", listOf("t", "why")) { it.logMovementTriggerIgnoredNonExit("ENTER") },
            Triple("receiverSkipped", listOf("why")) { it.logReceiverSkipped("no identified user") },
            Triple("geofencingError", listOf("ok", "code")) { it.logGeofencingError(1000) },
            Triple("transitionEmitting", listOf("id", "t")) { it.logTransitionEmitting("notl_core", "ENTER") },
            Triple("transitionSuppressed", listOf("id", "t", "why", "cd")) { it.logTransitionSuppressed("notl_core", "ENTER", 42.0) },
            Triple("initialEnterInside", listOf("id", "t", "why")) { it.logInitialEnterInside("notl_core") },
            Triple("droppedUnknownId", listOf("id", "why")) { it.logTransitionDroppedUnknownId("notl_core") },
            Triple("enterDroppedAlreadyReported", listOf("id", "t", "why")) { it.logEnterDroppedAlreadyReported("notl_core") },
            Triple("exitDroppedNeverEntered", listOf("id", "t", "why")) { it.logExitDroppedNeverEntered("notl_core") },
            Triple("droppedAnonymous", listOf("id", "t", "why")) { it.logTransitionDroppedAnonymous("notl_core", "EXIT") },
            Triple("syncTriggered", listOf("why")) { it.logSyncTriggered("app-launch") },
            Triple("syncSkipped", listOf("why")) { it.logSyncSkipped("no identified user") },
            Triple("syncSkippedNoLocation", listOf("why", "ctx")) { it.logSyncSkippedNoLocation("app-launch") },
            Triple("syncSkippedInvalidLocation", listOf("why", "ctx")) { it.logSyncSkippedInvalidLocation("app-launch", 0.0, 0.0) },
            Triple("syncSkippedNoPermission", listOf("why", "ctx")) { it.logSyncSkippedNoPermission("boot-restore") },
            Triple("syncSkippedFresh", listOf("why")) { it.logSyncSkippedFresh() },
            Triple("syncFailed", listOf("ok", "why")) { it.logSyncFailed("timeout") },
            Triple("syncSucceeded", listOf("n", "mvmt")) { it.logSyncSucceeded(19, true) },
            Triple("apiFetchResult", listOf("ok", "n", "ms")) { it.logApiFetchResult(30, 420L) },

            Triple("unknownApiTransitionType", listOf("ok", "why", "value")) { it.logUnknownApiTransitionType("dwell") },
            Triple("movementRearmed", listOf("why")) { it.logMovementRearmedAfterFailedRefresh() },
            Triple("storageLoaded", listOf("n", "anchor")) { it.logStorageLoaded({ 30 }, true) },
            Triple("persistFailed", listOf("id", "t", "ok")) { it.logPersistFailed("notl_core", "ENTER") },
            Triple("deliveryRetryable", listOf("id", "t", "ok", "retry", "why")) { it.logEventDeliveryRetryable("notl_core", "ENTER", "socket timeout") },
            Triple("deliveryFailed", listOf("id", "t", "ok", "retry", "why")) { it.logEventDeliveryFailed("notl_core", "ENTER", "400 bad request") },
            Triple("eventInvalidInput", listOf("ok", "why")) { it.logEventInvalidInput(null, null) },
            Triple("deliveryDeferredAnonymous", listOf("id", "t", "why")) { it.logEventDeliveryDeferredAnonymous("notl_core", "ENTER") },
            Triple("eventDelivered", listOf("id", "t", "via")) { it.logEventDelivered("notl_core", "ENTER") },
            Triple("deliverySkippedAlreadyDelivered", listOf("id", "t", "why")) { it.logEventDeliverySkippedAlreadyDelivered("notl_core", "ENTER") },
            Triple("workerEntryMissing", listOf("key", "why")) { it.logEventWorkerEntryMissing("notl_core:ENTER") },
            Triple("flushSnapshot", listOf("n", "phase")) { it.logForegroundFlushSnapshot(3) },
            Triple("flushCancelled", listOf("id", "t", "why")) { it.logForegroundFlushCancelledWorkManager("notl_core", "ENTER") },
            Triple("flushPublished", listOf("id", "t", "via")) { it.logForegroundFlushPublished("notl_core", "ENTER") },
            Triple("flushEntryFailed", listOf("id", "t", "ok", "via", "why")) { it.logForegroundFlushEntryFailed("notl_core", "ENTER", "boom") },
            Triple("flushComplete", listOf("n", "phase", "ok")) { it.logForegroundFlushComplete(3) },
            Triple("asyncDeliveryFailed", listOf("id", "t", "ok", "why")) { it.logAsyncDeliveryFailed("notl_core", "ENTER", "boom") },
            Triple("schedulerFailed", listOf("id", "t", "ok", "why", "detail")) { it.logSchedulerFailed("notl_core", "ENTER", "boom") }
        )
    }

    // MARK: - Contract

    @Test
    fun everyRecord_givenAnyMethod_expectMachineKeyAndReplayClassification() {
        for ((name, requiredKeys, run) in invocations()) {
            val logger = CapturingLogger()
            run(GeofenceLogger(logger))

            // Last message, not first: the precise-location warning would precede a gated record.
            val message = logger.messages.lastOrNull()
            message.shouldNotBeNull()
            val fields = parseTail(message)
            fields.shouldNotBeNull()

            fields["ev"].shouldNotBeNull()
            (fields["io"] in listOf("in", "out", "obs")) shouldBeEqualTo true
            for (key in requiredKeys) {
                if (fields[key] == null) {
                    throw AssertionError("$name: missing $key= in '$message'")
                }
            }
        }
    }

    @Test
    fun everyRecord_givenAnyMethod_expectProseAndTailSeparated() {
        for ((name, _, run) in invocations()) {
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
        for ((name, _, run) in invocations()) {
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

    // MARK: - The gate

    @Test
    fun everyRecord_givenDiagnosticsOff_expectOutputUnchangedFromBeforeInstrumentation() {
        GeofenceDiagnostics.setEnabledForTesting(false)

        for ((name, _, run) in invocations()) {
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
        for ((_, _, run) in invocations()) run(target)

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
        for ((_, _, run) in invocations()) run(target)

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
        for ((name, _, run) in invocations()) {
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
    fun sanitize_givenWhitespaceInIdentifier_expectFolded() {
        geofenceLogger.logTransitionEmitting("niagara on the lake", "ENTER")

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
            GeofenceLogger(logger).logTransitionEmitting(raw, "ENTER")

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

        subject.logRankEvaluated(
            candidates = 50,
            selectedCount = 19,
            selected = { selectedCalls++; listOf("alpha") },
            evicted = { evictedCalls++; listOf("beta") },
            edgeDistances = { distanceCalls++; mapOf("alpha" to 120.0) }
        )
        subject.logStorageLoaded(regionCount = { regionCountCalls++; 100 }, hasAnchor = true)

        selectedCalls shouldBeEqualTo 0
        evictedCalls shouldBeEqualTo 0
        distanceCalls shouldBeEqualTo 0
        regionCountCalls shouldBeEqualTo 0

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

        selectedCalls shouldBeEqualTo 1
        evictedCalls shouldBeEqualTo 1
        distanceCalls shouldBeEqualTo 1
        regionCountCalls shouldBeEqualTo 1
    }

    private fun catalogRegion(
        id: String = "11125",
        name: String? = "Momo Dubai Test"
    ) = GeofenceRegion(
        id = id,
        latitude = 25.109908,
        longitude = 55.184004,
        radius = 150f,
        name = name,
        geosetIds = listOf("4471", "9002")
    )

    @Test
    fun fenceCatalog_givenNameWithSeparators_expectSanitizedButReadable() {
        // A workspace-authored name is untrusted text in a format whose only structure is spaces
        // and `=`. Left raw, `Momo Dubai Test` would split into three bogus fields.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L, listOf(catalogRegion(name = "Momo Dubai, Test=1")))

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
        GeofenceLogger(logger).logApiFetchResult(1, 10L, listOf(catalogRegion()))

        val message = logger.messages.last()
        message.startsWith("[Geofence] ") shouldBeEqualTo true
        val fields = parseTail(message)
        fields.shouldNotBeNull()
        fields["ev"] shouldBeEqualTo "fence.cataloged"
        fields["io"] shouldBeEqualTo "in"
        for (key in listOf("id", "name", "gs", "lat", "lon", "rad", "tt")) {
            if (fields[key] == null) throw AssertionError("missing $key= in '$message'")
        }
    }

    @Test
    fun fenceCatalog_expectOneRecordPerFence() {
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(
            3,
            10L,
            listOf(catalogRegion(id = "1"), catalogRegion(id = "2"), catalogRegion(id = "3"))
        )
        logger.messages.count { it.contains("ev=fence.cataloged") } shouldBeEqualTo 3
    }

    @Test
    fun fenceCatalog_givenGeosetList_expectSeparatorsPreserved() {
        // `gs` and `tt` compose commas on purpose, like `ids` and `ranked` — they must opt out of
        // sanitising or the list collapses into one token.
        GeofenceDiagnostics.setEnabledForTesting(true)
        val logger = CapturingLogger()
        GeofenceLogger(logger).logApiFetchResult(1, 10L, listOf(catalogRegion()))

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
        GeofenceLogger(logger).logApiFetchResult(1, 10L, listOf(catalogRegion()))

        logger.messages.none { it.contains("catalogued") } shouldBeEqualTo true
    }
}
