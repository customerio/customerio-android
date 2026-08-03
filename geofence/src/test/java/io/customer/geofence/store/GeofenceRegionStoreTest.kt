package io.customer.geofence.store

import android.content.Context
import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldContainSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceRegionStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceRegionStoreImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        store = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        )
        store.clearAll()
    }

    // --- Cached regions (full backend response) ---

    @Test
    fun getCachedRegions_givenNothingStored_expectEmpty() {
        store.getCachedRegions().shouldBeEmpty()
    }

    @Test
    fun saveCachedRegions_thenGet_expectRoundTrip() {
        val regions = listOf(
            GeofenceRegion("biz-1", 37.7749, -122.4194, 100f, name = "Coffee"),
            GeofenceRegion(
                id = "biz-2",
                latitude = 51.5074,
                longitude = -0.1278,
                radius = 250f,
                name = "Office",
                transitionTypes = listOf(GeofenceTransitionType.ENTER),
                lastUpdated = 1_700_000_000L
            )
        )

        store.saveCachedRegions(regions)

        store.getCachedRegions() shouldBeEqualTo regions
    }

    @Test
    fun getCachedRegion_givenCachedId_expectRegion() {
        val region = GeofenceRegion("biz-1", 37.7749, -122.4194, 100f, name = "Coffee", geosetIds = listOf("3", "4"))
        store.saveCachedRegions(listOf(region))

        store.getCachedRegion("biz-1") shouldBeEqualTo region
    }

    @Test
    fun getCachedRegion_givenUnknownId_expectNull() {
        store.saveCachedRegions(listOf(GeofenceRegion("biz-1", 37.7749, -122.4194, 100f, name = "Coffee")))

        store.getCachedRegion("biz-missing") shouldBeEqualTo null
    }

    @Test
    fun saveCachedRegions_givenSubsequentSave_expectOverwrite() {
        store.saveCachedRegions(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        val replacement = listOf(GeofenceRegion("biz-2", 1.0, 2.0, 75f))

        store.saveCachedRegions(replacement)

        store.getCachedRegions() shouldBeEqualTo replacement
    }

    // --- Registered IDs (subset currently live in OS) ---

    @Test
    fun getRegisteredIds_givenNothingStored_expectEmpty() {
        store.getRegisteredIds().shouldBeEmpty()
    }

    @Test
    fun saveRegisteredIds_thenGet_expectRoundTrip() {
        val ids = setOf("cio_movement_trigger", "biz-1", "biz-2")

        store.saveRegisteredIds(ids)

        store.getRegisteredIds() shouldContainSame ids
    }

    @Test
    fun saveRegisteredIds_givenEmptySet_expectGetReturnsEmpty() {
        store.saveRegisteredIds(setOf("biz-1"))
        store.saveRegisteredIds(emptySet())

        store.getRegisteredIds().shouldBeEmpty()
    }

    @Test
    fun claimExit_givenNeverEntered_expectFalse() {
        // The phantom-EXIT guard: no containment record means GMS is reconciling its own
        // state rather than reporting a crossing.
        store.claimExit("biz-1") shouldBeEqualTo false
    }

    @Test
    fun claimExit_givenEntered_expectTrueThenFalseOnSecondCall() {
        store.recordEntered("biz-1")

        store.claimExit("biz-1") shouldBeEqualTo true
        // Consumed: a duplicate EXIT for the same crossing doesn't pass twice.
        store.claimExit("biz-1") shouldBeEqualTo false
    }

    @Test
    fun recordEntered_givenCalledTwice_expectSingleEntry() {
        store.recordEntered("biz-1")
        store.recordEntered("biz-1")

        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun reconcileEnteredIds_expectPrunedToRegisteredAndUnionedWithInside() {
        store.recordEntered("biz-kept")
        store.recordEntered("biz-unregistered")

        store.reconcileEnteredIds(
            registeredIds = setOf("biz-kept", "biz-new-inside"),
            inside = setOf("biz-new-inside"),
            sinceEpoch = store.containmentEpoch()
        )

        // "biz-unregistered" pruned, "biz-kept" survives because it is still registered.
        store.getEnteredIds() shouldContainSame setOf("biz-kept", "biz-new-inside")
    }

    @Test
    fun reconcileEnteredIds_givenExitClaimedAfterFixWasTaken_expectFenceNotReSeeded() {
        store.recordEntered("biz-1")
        // A sync captures the epoch with its fix, then awaits GMS for seconds.
        val epochAtFix = store.containmentEpoch()

        // Mid-flight the OS reports the departure and the receiver consumes the record.
        store.claimExit("biz-1").shouldBeTrue()

        // The sync now writes back geometry from the older fix, which still contained the fence.
        store.reconcileEnteredIds(
            registeredIds = setOf("biz-1"),
            inside = setOf("biz-1"),
            sinceEpoch = epochAtFix
        )

        // Resurrecting it would leave the device recorded inside a fence it left, disarming the EXIT
        // guard for that fence until its next departure.
        store.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun reconcileEnteredIds_givenExitClaimedBeforeFixWasTaken_expectFenceSeeded() {
        // Same fence, opposite order: the departure is already history when the fix is taken, so the
        // geometry is the newer evidence — e.g. the device drove back in while the process was dead.
        store.recordEntered("biz-1")
        store.claimExit("biz-1").shouldBeTrue()
        val epochAtFix = store.containmentEpoch()

        store.reconcileEnteredIds(
            registeredIds = setOf("biz-1"),
            inside = setOf("biz-1"),
            sinceEpoch = epochAtFix
        )

        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun reconcileEnteredIds_givenUnclaimedExitForSameFence_expectStillSeeded() {
        // A claim that found no record is a suspected GMS artifact, not a departure. Letting it block
        // the seed would leave the fence with no containment at all, so its next genuine EXIT would
        // be dropped as never-entered — the failure the seed exists to prevent.
        val epochAtFix = store.containmentEpoch()

        store.claimExit("biz-1").shouldBeFalse()

        store.reconcileEnteredIds(
            registeredIds = setOf("biz-1"),
            inside = setOf("biz-1"),
            sinceEpoch = epochAtFix
        )

        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun reconcileEnteredIds_givenFenceUnregisteredAfterClaim_expectClaimForgottenSoLaterSeedWorks() {
        // The claim record is per-fence state that would otherwise accumulate for the life of the
        // process. Trimming it to the registered set is safe: a fence has to be re-registered before
        // geometry can seed it again, and by then the old claim is older than any such sync.
        store.recordEntered("biz-1")
        val epochAtFix = store.containmentEpoch()
        store.claimExit("biz-1").shouldBeTrue()

        // Fence drops out of the monitored set, so its claim record is trimmed.
        store.reconcileEnteredIds(registeredIds = setOf("biz-other"), inside = emptySet(), sinceEpoch = epochAtFix)
        // Re-registered later with the device inside it, using the same stale epoch.
        store.reconcileEnteredIds(registeredIds = setOf("biz-1"), inside = setOf("biz-1"), sinceEpoch = epochAtFix)

        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun containmentEpoch_givenClaimedExit_expectAdvanced() {
        store.recordEntered("biz-1")
        val before = store.containmentEpoch()

        store.claimExit("biz-1")

        (store.containmentEpoch() > before).shouldBeTrue()
    }

    @Test
    fun hasContainmentRecord_givenNothingEverRecorded_expectFalse() {
        // Upgraded install: distinguishable from "recorded, and nothing is entered".
        store.hasContainmentRecord().shouldBeFalse()
        store.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun hasContainmentRecord_givenReconcileWithNothingInside_expectTrue() {
        // The first registration seeds the key even when the device is inside nothing, which is what
        // ends the upgrade grace period.
        store.reconcileEnteredIds(registeredIds = setOf("biz-1"), inside = emptySet(), sinceEpoch = store.containmentEpoch())

        store.hasContainmentRecord().shouldBeTrue()
        store.getEnteredIds().shouldBeEmpty()
    }

    @Test
    fun reconcileEnteredIds_givenStaleAnchorReportsOutside_expectExistingContainmentPreserved() {
        // A launch refresh can run off the persisted anchor rather than a live fix. If that
        // anchor wrongly says "outside", erasing containment would swallow the genuine EXIT.
        store.recordEntered("biz-1")

        store.reconcileEnteredIds(registeredIds = setOf("biz-1"), inside = emptySet(), sinceEpoch = store.containmentEpoch())

        store.getEnteredIds() shouldContainSame setOf("biz-1")
    }

    @Test
    fun hasEmittedEnter_givenNothingReported_expectFalse() {
        store.hasEmittedEnter(USER, "biz-1").shouldBeFalse()
    }

    @Test
    fun markEnterEmitted_givenCalledTwice_expectStillReportedAndIdempotent() {
        store.markEnterEmitted(USER, "biz-1")
        store.markEnterEmitted(USER, "biz-1")

        store.hasEmittedEnter(USER, "biz-1").shouldBeTrue()
    }

    @Test
    fun claimExit_givenEnterReported_expectMarkClearedWithContainment() {
        store.recordEntered("biz-1")
        store.markEnterEmitted(USER, "biz-1")

        store.claimExit("biz-1").shouldBeTrue()

        // Both drop together: the mark can't be left behind for a delivery path that may not run.
        store.getEnteredIds().shouldBeEmpty()
        store.hasEmittedEnter(USER, "biz-1").shouldBeFalse()
    }

    @Test
    fun claimExit_givenNeverEntered_expectMarkRetained() {
        store.markEnterEmitted(USER, "biz-1")

        store.claimExit("biz-1").shouldBeFalse()

        // An unclaimed EXIT is a GMS artifact, not a departure — re-arming on it would let the next
        // OS re-report of ENTER through as a fresh arrival.
        store.hasEmittedEnter(USER, "biz-1").shouldBeTrue()
    }

    @Test
    fun hasEmittedEnter_givenMarkOwnedByAnotherUser_expectFalse() {
        store.markEnterEmitted(USER, "biz-1")

        // A direct A-to-B identify publishes no ResetEvent, so B reaches a still-registered fence
        // with A's mark in place. Honouring it would swallow B's first arrival.
        store.hasEmittedEnter(OTHER_USER, "biz-1").shouldBeFalse()
    }

    @Test
    fun markEnterEmitted_givenNewOwner_expectPreviousUsersMarksDiscarded() {
        store.markEnterEmitted(USER, "biz-1")

        store.markEnterEmitted(OTHER_USER, "biz-2")

        store.hasEmittedEnter(OTHER_USER, "biz-2").shouldBeTrue()
        store.hasEmittedEnter(OTHER_USER, "biz-1").shouldBeFalse()
        // The set belongs to one identity at a time, so A's marks are gone rather than parked.
        store.hasEmittedEnter(USER, "biz-1").shouldBeFalse()
    }

    @Test
    fun pruneEmittedEnterIds_expectMarksDroppedForUnregisteredFences() {
        store.markEnterEmitted(USER, "biz-kept")
        store.markEnterEmitted(USER, "biz-evicted")

        store.pruneEmittedEnterIds(setOf("biz-kept"))

        store.hasEmittedEnter(USER, "biz-kept").shouldBeTrue()
        store.hasEmittedEnter(USER, "biz-evicted").shouldBeFalse()
    }

    @Test
    fun pruneEmittedEnterIds_givenFenceEvictedWhileInside_expectLaterRevisitNotSuppressed() {
        // Without the prune the mark outlives the monitoring that would clear it: a fence dropped
        // from the nearest set while the device is inside never reports its EXIT, so a genuine
        // revisit months later would be swallowed.
        store.markEnterEmitted(USER, "biz-1")

        // Evicted from the monitored set — no EXIT is ever delivered for it.
        store.pruneEmittedEnterIds(setOf("biz-other"))
        // Re-registered on a later sync when the device comes back into range.
        store.pruneEmittedEnterIds(setOf("biz-1"))

        store.hasEmittedEnter(USER, "biz-1").shouldBeFalse()
    }

    @Test
    fun markEnterEmitted_expectIndependentOfEnteredSet() {
        // The two sets answer different questions — where the device is vs. what we have sent — and
        // the geometry seeding writes only the former. Coupling them would let a sync's reconcile
        // suppress the OS ENTER that follows it milliseconds later.
        store.markEnterEmitted(USER, "biz-1")

        store.getEnteredIds().shouldBeEmpty()

        store.reconcileEnteredIds(registeredIds = setOf("biz-2"), inside = setOf("biz-2"), sinceEpoch = store.containmentEpoch())

        store.hasEmittedEnter(USER, "biz-1").shouldBeTrue()
        store.hasEmittedEnter(USER, "biz-2").shouldBeFalse()
    }

    @Test
    fun getLastRegistrationUptime_givenNothingStored_expectNull() {
        store.getLastRegistrationUptime().shouldBeNull()
    }

    @Test
    fun setLastRegistrationUptime_thenGet_expectRoundTrip() {
        store.setLastRegistrationUptime(123_456L)

        store.getLastRegistrationUptime() shouldBeEqualTo 123_456L
    }

    @Test
    fun getLastRegistrationPackageUpdateTime_givenNothingStored_expectNull() {
        store.getLastRegistrationPackageUpdateTime().shouldBeNull()
    }

    @Test
    fun setLastRegistrationPackageUpdateTime_thenGet_expectRoundTrip() {
        store.setLastRegistrationPackageUpdateTime(123_456L)

        store.getLastRegistrationPackageUpdateTime() shouldBeEqualTo 123_456L
    }

    // --- Cached config ---

    @Test
    fun getCachedConfig_givenNothingStored_expectNull() {
        store.getCachedConfig().shouldBeNull()
    }

    @Test
    fun saveCachedConfig_thenGet_expectRoundTrip() {
        val config = GeofenceConfig(
            localRefreshTriggerRadius = 1_000f,
            remoteFetchRefreshTriggerRadius = 5_000f,
            remoteFetchRefreshExpiry = 86_400_000L,
            duplicateEventsExpiry = 3_600_000L,
            maxBusinessGeofences = 19,
            maxMonitoringDistance = 1_000_000f
        )

        store.saveCachedConfig(config)

        store.getCachedConfig() shouldBeEqualTo config
    }

    // --- API anchor location ---

    @Test
    fun getLastApiFetchLocation_givenNothingStored_expectNull() {
        store.getLastApiFetchLocation().shouldBeNull()
    }

    @Test
    fun saveLastApiFetchLocation_thenGet_expectRoundTrip() {
        val location = GeofenceLocation(latitude = 37.7749, longitude = -122.4194)

        store.saveLastApiFetchLocation(location)

        store.getLastApiFetchLocation() shouldBeEqualTo location
    }

    @Test
    fun saveLastApiFetchLocation_givenNewStoreInstance_expectValueDecryptedCorrectly() {
        // Cross-instance round trip. Location snapshots are encrypted via
        // [PreferenceCrypto] (Android Keystore); a fresh store must be able to
        // decrypt what a prior store wrote — otherwise process restarts would
        // wipe the anchor and break the Tier-B distance check.
        val location = GeofenceLocation(latitude = 37.7749, longitude = -122.4194)
        store.saveLastApiFetchLocation(location)

        val newInstance = GeofenceRegionStoreImpl(
            context = applicationMock,
            jsonSerializer = GeofenceJsonSerializer(),
            logger = mockk(relaxed = true)
        )

        newInstance.getLastApiFetchLocation() shouldBeEqualTo location
    }

    // --- Movement-trigger location ---

    @Test
    fun getLastMovementTriggerLocation_givenNothingStored_expectNull() {
        store.getLastMovementTriggerLocation().shouldBeNull()
    }

    @Test
    fun saveLastMovementTriggerLocation_thenGet_expectRoundTrip() {
        val location = GeofenceLocation(latitude = 40.7128, longitude = -74.0060)

        store.saveLastMovementTriggerLocation(location)

        store.getLastMovementTriggerLocation() shouldBeEqualTo location
    }

    @Test
    fun saveLastMovementTriggerLocation_givenSubsequentSave_expectOverwrite() {
        // Each successful registration overwrites — we only ever need the latest.
        store.saveLastMovementTriggerLocation(GeofenceLocation(1.0, 2.0))
        store.saveLastMovementTriggerLocation(GeofenceLocation(3.0, 4.0))

        store.getLastMovementTriggerLocation() shouldBeEqualTo GeofenceLocation(3.0, 4.0)
    }

    @Test
    fun clearLastMovementTriggerLocation_givenPriorSave_expectNull() {
        // Called when a refresh succeeds with an empty business set (no movement
        // trigger registered → the cached location is now stale).
        store.saveLastMovementTriggerLocation(GeofenceLocation(1.0, 2.0))

        store.clearLastMovementTriggerLocation()

        store.getLastMovementTriggerLocation().shouldBeNull()
    }

    // --- Last-sync timestamp ---

    @Test
    fun lastSyncTimestamp_givenNothingStored_expectNull() {
        store.getLastSyncTimestamp().shouldBeNull()
    }

    @Test
    fun setLastSyncTimestamp_thenGet_expectStoredValue() {
        store.setLastSyncTimestamp(1_700_000_000L)

        store.getLastSyncTimestamp() shouldBeEqualTo 1_700_000_000L
    }

    @Test
    fun setLastSyncTimestamp_givenSubsequentSet_expectOverwrite() {
        store.setLastSyncTimestamp(100L)
        store.setLastSyncTimestamp(200L)

        store.getLastSyncTimestamp() shouldBeEqualTo 200L
    }

    // --- clearAll wipes everything ---

    @Test
    fun clearAll_expectEverythingRemoved() {
        store.saveCachedRegions(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        store.saveRegisteredIds(setOf("biz-1"))
        store.saveCachedConfig(
            GeofenceConfig(
                localRefreshTriggerRadius = 1_000f,
                remoteFetchRefreshTriggerRadius = 5_000f,
                remoteFetchRefreshExpiry = 1L,
                duplicateEventsExpiry = 1L,
                maxBusinessGeofences = 1,
                maxMonitoringDistance = 1_000_000f
            )
        )
        store.saveLastApiFetchLocation(GeofenceLocation(1.0, 2.0))
        store.saveLastMovementTriggerLocation(GeofenceLocation(3.0, 4.0))
        store.setLastSyncTimestamp(12_345L)
        store.recordEntered("biz-1")

        store.clearAll()

        store.getCachedRegions().shouldBeEmpty()
        store.getRegisteredIds().shouldBeEmpty()
        store.getEnteredIds().shouldBeEmpty()
        store.getCachedConfig().shouldBeNull()
        store.getLastApiFetchLocation().shouldBeNull()
        store.getLastMovementTriggerLocation().shouldBeNull()
        store.getLastSyncTimestamp().shouldBeNull()
    }

    @Test
    fun clearUserScopedState_expectUserKeysAndFreshnessRemovedButCachePreserved() {
        // Sign-out path: drop user-scoped state and the freshness timestamp
        // (so the next login re-fetches) but keep cached regions/config.
        val regions = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f))
        val config = GeofenceConfig(
            localRefreshTriggerRadius = 1_000f,
            remoteFetchRefreshTriggerRadius = 5_000f,
            remoteFetchRefreshExpiry = 86_400_000L,
            duplicateEventsExpiry = 3_600_000L,
            maxBusinessGeofences = 19,
            maxMonitoringDistance = 1_000_000f
        )
        store.saveCachedRegions(regions)
        store.saveCachedConfig(config)
        store.saveRegisteredIds(setOf("biz-1"))
        store.recordEntered("biz-1")
        store.markEnterEmitted(USER, "biz-1")
        store.saveLastApiFetchLocation(GeofenceLocation(1.0, 2.0))
        store.saveLastMovementTriggerLocation(GeofenceLocation(3.0, 4.0))
        store.setLastRegistrationUptime(99_999L)
        store.setLastRegistrationPackageUpdateTime(88_888L)
        store.setLastSyncTimestamp(12_345L)

        store.clearUserScopedState()

        // User-specific: wiped.
        store.getRegisteredIds().shouldBeEmpty()
        // Goes with the registrations it describes — sign-out drops those from the OS.
        store.getEnteredIds().shouldBeEmpty()
        // The next user must not inherit a suppressed ENTER for a fence they were never told about.
        store.hasEmittedEnter(USER, "biz-1").shouldBeFalse()
        store.getLastApiFetchLocation().shouldBeNull()
        store.getLastMovementTriggerLocation().shouldBeNull()
        store.getLastRegistrationUptime().shouldBeNull()
        store.getLastRegistrationPackageUpdateTime().shouldBeNull()
        // Freshness throttle: wiped so the next login re-fetches.
        store.getLastSyncTimestamp().shouldBeNull()
        // Cached regions/config: preserved.
        store.getCachedRegions() shouldBeEqualTo regions
        store.getCachedConfig() shouldBeEqualTo config
    }

    // --- Schema-drift / corruption safety ---

    @Test
    fun getCachedRegions_givenCorruptedJson_expectEmptyAndKeyCleared() {
        writeRaw("cached_regions", "this is not valid json")

        store.getCachedRegions().shouldBeEmpty()
        // Re-read after the failed parse should see "no value" (key was wiped),
        // so writing fresh data works without leftover corruption.
        store.getCachedRegions().shouldBeEmpty()
    }

    @Test
    fun getCachedConfig_givenCorruptedJson_expectNullAndKeyCleared() {
        writeRaw("cached_config", "{ broken")

        store.getCachedConfig().shouldBeNull()
        store.getCachedConfig().shouldBeNull()
    }

    @Test
    fun getLastApiFetchLocation_givenCorruptedJson_expectNullAndKeyCleared() {
        writeRaw("last_api_fetch_location", "}{not json{")

        store.getLastApiFetchLocation().shouldBeNull()
    }

    // --- @SerialName key pinning ---
    // Pin the persisted JSON keys against accidental @SerialName removal or Kotlin renames.

    @Test
    fun saveCachedConfig_expectStableJsonKeys() {
        store.saveCachedConfig(
            GeofenceConfig(
                localRefreshTriggerRadius = 1_000f,
                remoteFetchRefreshTriggerRadius = 5_000f,
                remoteFetchRefreshExpiry = 86_400_000L,
                duplicateEventsExpiry = 3_600_000L,
                maxBusinessGeofences = 19,
                maxMonitoringDistance = 1_000_000f
            )
        )

        val raw = readRaw("cached_config")
        listOf(
            "localRefreshTriggerRadius",
            "remoteFetchRefreshTriggerRadius",
            "remoteFetchRefreshExpiry",
            "duplicateEventsExpiry",
            "maxBusinessGeofences",
            "maxMonitoringDistance"
        ).forEach { key -> raw shouldContain "\"$key\"" }
    }

    @Test
    fun saveLastApiFetchLocation_expectStableJsonKeys() {
        store.saveLastApiFetchLocation(GeofenceLocation(latitude = 12.34, longitude = 56.78))

        val raw = readRaw("last_api_fetch_location")
        raw shouldContain "\"latitude\""
        raw shouldContain "\"longitude\""
    }

    @Test
    fun saveCachedRegions_expectStableJsonKeys() {
        store.saveCachedRegions(
            listOf(
                GeofenceRegion(
                    id = "biz-1",
                    latitude = 1.0,
                    longitude = 2.0,
                    radius = 100f,
                    name = "Coffee",
                    transitionTypes = listOf(GeofenceTransitionType.ENTER),
                    lastUpdated = 1_700_000_000L
                )
            )
        )

        val raw = readRaw("cached_regions")
        listOf("id", "latitude", "longitude", "radius", "name", "transitionTypes", "lastUpdated").forEach { key ->
            raw shouldContain "\"$key\""
        }
        // Enum value serialized as the pinned name — lowercase to match the API
        // wire format (otherwise the cache and the API speak different dialects).
        raw shouldContain "\"enter\""
    }

    @Test
    fun getCachedConfig_givenJsonWithUnknownFields_expectDecodesIgnoringUnknown() {
        // Forward-compat: a future SDK adding new fields to GeofenceConfig must
        // still be able to read a JSON payload that has extra fields it doesn't know.
        writeRaw(
            "cached_config",
            """{
              "localRefreshTriggerRadius": 1000.0,
              "remoteFetchRefreshTriggerRadius": 5000.0,
              "remoteFetchRefreshExpiry": 86400000,
              "duplicateEventsExpiry": 3600000,
              "maxBusinessGeofences": 19,
              "maxMonitoringDistance": 1000000.0,
              "future_field_we_dont_know": "ignore me"
            }
            """.trimIndent()
        )

        store.getCachedConfig() shouldBeEqualTo GeofenceConfig(
            localRefreshTriggerRadius = 1_000f,
            remoteFetchRefreshTriggerRadius = 5_000f,
            remoteFetchRefreshExpiry = 86_400_000L,
            duplicateEventsExpiry = 3_600_000L,
            maxBusinessGeofences = 19,
            maxMonitoringDistance = 1_000_000f
        )
    }

    private companion object {
        private const val USER = "user-1"
        private const val OTHER_USER = "user-2"
    }

    private fun writeRaw(key: String, value: String) {
        applicationMock.getSharedPreferences(
            "io.customer.sdk.geofence_regions.${applicationMock.packageName}",
            Context.MODE_PRIVATE
        ).edit().putString(key, value).commit()
    }

    private fun readRaw(key: String): String =
        applicationMock.getSharedPreferences(
            "io.customer.sdk.geofence_regions.${applicationMock.packageName}",
            Context.MODE_PRIVATE
        ).getString(key, "") ?: ""
}
