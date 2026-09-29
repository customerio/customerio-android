package io.customer.geofence.replay

import android.location.Location
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.polygon.PolygonFixPriority
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplayPolygonFreshFixSourceTest : RobolectricTest() {

    @Test
    fun deliver_givenNoOpenRequest_expectReportedUnanswered() {
        ReplayPolygonFreshFixSource().deliver(Location("replay")) shouldBeEqualTo false
    }

    @Test
    fun deliver_givenAnOpenRequest_expectTheRequestAnswered() = runTest {
        val source = ReplayPolygonFreshFixSource()
        val fix = Location("replay")
        val request = async { source.awaitFreshFix(timeoutMs = 10_000, priority = PolygonFixPriority.HIGH_ACCURACY) }
        runCurrent()

        source.deliver(fix) shouldBeEqualTo true
        request.await() shouldBeEqualTo fix
    }

    /** A request that already timed out is closed, so its late answer is unanswered too. */
    @Test
    fun deliver_givenTheRequestTimedOut_expectReportedUnanswered() = runTest {
        val source = ReplayPolygonFreshFixSource()
        source.awaitFreshFix(timeoutMs = 1_000, priority = PolygonFixPriority.HIGH_ACCURACY) shouldBeEqualTo null

        source.deliver(Location("replay")) shouldBeEqualTo false
    }
}
