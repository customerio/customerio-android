package io.customer.geofence.store

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.communication.Event
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeofenceCooldownStoreTest : RobolectricTest() {

    private lateinit var store: GeofenceCooldownStoreImpl

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                argument(ApplicationArgument(applicationMock))
            }
        )
        store = GeofenceCooldownStoreImpl(applicationMock)
        store.clearAll()
    }

    @Test
    fun getLastEmitTimestamp_givenNothingStored_expectNull() {
        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
    }

    @Test
    fun recordEmit_thenGetLastEmitTimestamp_expectStoredValue() {
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 1_234L)

        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 1_234L
    }

    @Test
    fun recordEmit_givenMultipleKeys_expectIndependentValues() {
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 100L)
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.EXIT, 200L)
        store.recordEmit("user-1", "biz-2", Event.GeofenceTransition.ENTER, 300L)

        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 100L
        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.EXIT) shouldBeEqualTo 200L
        store.getLastEmitTimestamp("user-1", "biz-2", Event.GeofenceTransition.ENTER) shouldBeEqualTo 300L
    }

    @Test
    fun recordEmit_givenSameFenceDifferentUsers_expectIndependentValues() {
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 100L)

        store.getLastEmitTimestamp("user-2", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        store.recordEmit("user-2", "biz-1", Event.GeofenceTransition.ENTER, 200L)
        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 100L
        store.getLastEmitTimestamp("user-2", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 200L
    }

    @Test
    fun recordEmit_givenSameKey_expectOverwrite() {
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 100L)
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 200L)

        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 200L
    }

    @Test
    fun recordEmit_givenNewStoreInstance_expectValuePersisted() {
        // Cooldown state must survive across SDK / process restarts — otherwise a
        // re-launch could fire a duplicate event still within the suppression window.
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 1_234L)

        val newInstance = GeofenceCooldownStoreImpl(applicationMock)

        newInstance.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER) shouldBeEqualTo 1_234L
    }

    @Test
    fun pruneOlderThan_expectOnlyEntriesBeforeCutoffRemoved() {
        store.recordEmit("user-1", "biz-old", Event.GeofenceTransition.ENTER, 100L)
        store.recordEmit("user-1", "biz-at-cutoff", Event.GeofenceTransition.ENTER, 500L)
        store.recordEmit("user-1", "biz-fresh", Event.GeofenceTransition.EXIT, 900L)

        store.pruneOlderThan(500L)

        store.getLastEmitTimestamp("user-1", "biz-old", Event.GeofenceTransition.ENTER).shouldBeNull()
        // Cutoff is exclusive: an entry recorded exactly at the cutoff survives.
        store.getLastEmitTimestamp("user-1", "biz-at-cutoff", Event.GeofenceTransition.ENTER) shouldBeEqualTo 500L
        store.getLastEmitTimestamp("user-1", "biz-fresh", Event.GeofenceTransition.EXIT) shouldBeEqualTo 900L
    }

    @Test
    fun pruneOlderThan_givenIdWithUnderscores_expectPruned() {
        // Keys embed the geofenceId between fixed prefix/suffix; underscores in the
        // id must not confuse the prefix scan.
        store.recordEmit("user-1", "biz_with_underscores", Event.GeofenceTransition.ENTER, 100L)

        store.pruneOlderThan(500L)

        store.getLastEmitTimestamp("user-1", "biz_with_underscores", Event.GeofenceTransition.ENTER).shouldBeNull()
    }

    @Test
    fun clearAll_expectAllValuesRemoved() {
        store.recordEmit("user-1", "biz-1", Event.GeofenceTransition.ENTER, 100L)
        store.recordEmit("user-1", "biz-2", Event.GeofenceTransition.EXIT, 200L)

        store.clearAll()

        store.getLastEmitTimestamp("user-1", "biz-1", Event.GeofenceTransition.ENTER).shouldBeNull()
        store.getLastEmitTimestamp("user-1", "biz-2", Event.GeofenceTransition.EXIT).shouldBeNull()
    }
}
