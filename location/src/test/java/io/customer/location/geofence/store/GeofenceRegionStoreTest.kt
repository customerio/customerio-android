package io.customer.location.geofence.store

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.location.geofence.GeofenceRegion
import io.customer.location.geofence.GeofenceTransitionType
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
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
        store = GeofenceRegionStoreImpl(applicationMock)
        store.clearAll()
    }

    @Test
    fun getAll_givenNothingStored_expectEmptyList() {
        store.getAll().shouldBeEmpty()
    }

    @Test
    fun saveAll_thenGetAll_expectStoredRegionsRoundTripped() {
        val regions = listOf(
            GeofenceRegion(id = "biz-1", latitude = 37.7749, longitude = -122.4194, radius = 100f, name = "Coffee"),
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

        store.saveAll(regions)

        store.getAll() shouldBeEqualTo regions
    }

    @Test
    fun saveAll_givenSubsequentSave_expectOverwrite() {
        val initial = listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f))
        val replacement = listOf(
            GeofenceRegion("biz-2", 1.0, 2.0, 75f),
            GeofenceRegion("biz-3", 3.0, 4.0, 100f)
        )

        store.saveAll(initial)
        store.saveAll(replacement)

        store.getAll() shouldBeEqualTo replacement
    }

    @Test
    fun saveAll_givenEmptyList_expectGetAllReturnsEmpty() {
        store.saveAll(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        store.saveAll(emptyList())

        store.getAll().shouldBeEmpty()
    }

    @Test
    fun clearAll_expectRegionsAndTimestampRemoved() {
        store.saveAll(listOf(GeofenceRegion("biz-1", 0.0, 0.0, 50f)))
        store.setLastSyncTimestamp(12_345L)

        store.clearAll()

        store.getAll().shouldBeEmpty()
        store.getLastSyncTimestamp().shouldBeNull()
    }

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

    @Test
    fun getAll_givenCorruptedJson_expectEmptyListNotThrow() {
        // Simulate corruption by writing garbage into the same prefs key.
        applicationMock.getSharedPreferences(
            "io.customer.sdk.geofence_regions.${applicationMock.packageName}",
            android.content.Context.MODE_PRIVATE
        ).edit().putString("regions", "this is not valid json").commit()

        store.getAll().shouldBeEmpty()
    }
}
