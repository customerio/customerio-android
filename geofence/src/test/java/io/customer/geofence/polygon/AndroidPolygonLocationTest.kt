package io.customer.geofence.polygon

import android.location.Location
import android.os.SystemClock
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidPolygonLocationTest : RobolectricTest() {

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfigurationDefault { })
    }

    @Test
    fun toPolygonLocationFix_givenCoordinateOutsideTheValidRange_expectRejected() {
        // A provider can report a corrupt position. Carrying it forward would let the evaluator
        // judge containment against a point that cannot exist.
        location(latitude = 95.0).toPolygonLocationFix() shouldBeEqualTo null
        location(longitude = -181.0).toPolygonLocationFix() shouldBeEqualTo null
        location(latitude = Double.NaN).toPolygonLocationFix() shouldBeEqualTo null
    }

    @Test
    fun toPolygonLocationFix_givenCoordinateInRange_expectAccepted() {
        // Control for the case above: same fix, coordinates the earth actually has.
        location().toPolygonLocationFix() shouldNotBeEqualTo null
    }

    @Test
    fun toPolygonLocationFix_givenUnusableAccuracyOrTimestamp_expectRejected() {
        location(accuracyMeters = 0f).toPolygonLocationFix() shouldBeEqualTo null
        location(elapsedRealtimeNanos = 0L).toPolygonLocationFix() shouldBeEqualTo null
    }

    private fun location(
        latitude: Double = 37.7750,
        longitude: Double = -122.4194,
        accuracyMeters: Float = 5f,
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) = Location("test").apply {
        this.latitude = latitude
        this.longitude = longitude
        this.accuracy = accuracyMeters
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        this.time = 100_000L
    }
}
