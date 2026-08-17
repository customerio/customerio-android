package io.customer.geofence.polygon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.location.LocationResult
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.di.geofenceCooldownFilter
import io.customer.geofence.di.geofenceCooldownStore
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.distanceTo
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device/emulator, not Robolectric.
 *
 * Executed by the `module-instrumentation-test` job in `.github/workflows/test.yml`
 * (`./gradlew :geofence:connectedDebugAndroidTest`, API 31 on the `google_apis` image). The job
 * requires test results to exist, so a run that compiled but executed nothing fails rather than
 * reporting green.
 */
@OptIn(InternalCustomerIOApi::class)
@RunWith(AndroidJUnit4::class)
class PolygonRouteScenarioIntegrationTest {
    @get:Rule
    val locationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKComponent.setupAndroidComponent(context)
        clearState()
        // Service teardown is asynchronous. Let an instance stopped by the preceding scenario
        // finish onDestroy before this scenario can start a new fine-location registration.
        SystemClock.sleep(500)
    }

    @After
    fun tearDown() {
        clearState()
    }

    @Test
    fun lShape_whenRouteWaitsInEnclosingCircleDeadSpace_thenOnlyTheArmProducesEntry() {
        val vertices = listOf(
            point(-200.0, -200.0),
            point(-200.0, 200.0),
            point(-50.0, 200.0),
            point(-50.0, -50.0),
            point(200.0, -50.0),
            point(200.0, -200.0)
        )
        val scenario = prepareScenario("l-shape", vertices)

        val route = route(
            RoutePoint(95.0, 95.0, accuracy = 5f),
            RoutePoint(100.0, 100.0, accuracy = 5f),
            RoutePoint(105.0, 105.0, accuracy = 5f),
            RoutePoint(95.0, -100.0, accuracy = 5f),
            RoutePoint(100.0, -100.0, accuracy = 5f),
            RoutePoint(105.0, -100.0, accuracy = 5f)
        )
        check(
            route.take(3).all {
                scenario.region.distanceTo(it.latitude, it.longitude) + it.accuracy <= scenario.region.radius
            }
        ) { "the concave dead-space fixes must remain inside the enclosing trigger circle" }

        scenario.processSynchronously(route)

        waitUntil { scenario.id in scenario.store.getEnteredIds() }
        scenario.transitions() shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun triangle_whenWalkingFixesArrive_thenProducesOneEntry() {
        val scenario = prepareScenario(
            id = "walking-triangle",
            vertices = listOf(
                point(160.0, 0.0),
                point(-120.0, -160.0),
                point(-120.0, 160.0)
            )
        )

        scenario.send(
            route(
                RoutePoint(0.0, -3.0, accuracy = 4f, speed = 1.4f),
                RoutePoint(0.0, 0.0, accuracy = 4f, speed = 1.4f),
                RoutePoint(0.0, 3.0, accuracy = 4f, speed = 1.4f)
            )
        )

        waitUntil { scenario.id in scenario.store.getEnteredIds() }
        scenario.transitions() shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun circleLikePolygon_whenDrivenThroughAtFifteenMetersPerSecond_thenProducesEntryAndExit() {
        val circleVertices = List(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            point(northMeters = 120.0 * sin(angle), eastMeters = 120.0 * cos(angle))
        }
        val scenario = prepareScenario("driving-circle", circleVertices)

        scenario.send(
            route(
                RoutePoint(0.0, -170.0, speed = 15f),
                RoutePoint(0.0, -80.0, speed = 15f),
                RoutePoint(0.0, -50.0, speed = 15f),
                RoutePoint(0.0, -20.0, speed = 15f),
                RoutePoint(0.0, 20.0, speed = 15f),
                RoutePoint(0.0, 50.0, speed = 15f),
                RoutePoint(0.0, 80.0, speed = 15f),
                RoutePoint(0.0, 140.0, speed = 15f),
                RoutePoint(0.0, 170.0, speed = 15f),
                RoutePoint(0.0, 200.0, speed = 15f)
            )
        )

        waitUntil { scenario.transitions().size == 2 }
        scenario.transitions() shouldBeEqualTo listOf(
            Event.GeofenceTransition.ENTER,
            Event.GeofenceTransition.EXIT
        )
        scenario.store.getEnteredIds() shouldBeEqualTo emptySet()
    }

    @Test
    fun square_whenGpsJittersAcrossBoundary_thenDoesNotFlickerBeforeClearExit() {
        val scenario = prepareScenario(
            id = "jitter-square",
            vertices = listOf(
                point(-100.0, -100.0),
                point(-100.0, 100.0),
                point(100.0, 100.0),
                point(100.0, -100.0)
            )
        )

        scenario.send(
            route(
                RoutePoint(0.0, 0.0, accuracy = 5f),
                RoutePoint(0.0, 3.0, accuracy = 5f),
                RoutePoint(0.0, 6.0, accuracy = 5f),
                RoutePoint(0.0, 98.0, accuracy = 15f),
                RoutePoint(0.0, 102.0, accuracy = 15f),
                RoutePoint(0.0, 97.0, accuracy = 12f),
                RoutePoint(0.0, 103.0, accuracy = 12f),
                RoutePoint(0.0, 99.0, accuracy = 15f),
                RoutePoint(0.0, 125.0, accuracy = 5f),
                RoutePoint(0.0, 135.0, accuracy = 5f),
                RoutePoint(0.0, 145.0, accuracy = 5f)
            )
        )

        waitUntil { scenario.transitions().size == 2 }
        scenario.transitions() shouldBeEqualTo listOf(
            Event.GeofenceTransition.ENTER,
            Event.GeofenceTransition.EXIT
        )
    }

    @Test
    fun narrowCorridor_whenFastSparseFixesJumpAcross_thenDoesNotInventAVisit() {
        val scenario = prepareScenario(
            id = "fast-corridor",
            vertices = listOf(
                point(-6.0, -200.0),
                point(-6.0, 200.0),
                point(6.0, 200.0),
                point(6.0, -200.0)
            )
        )

        val sparseRoute = route(
            RoutePoint(-60.0, 0.0, accuracy = 5f, speed = 30f),
            RoutePoint(60.0, 0.0, accuracy = 5f, speed = 30f)
        )
        check(
            sparseRoute.all {
                scenario.region.distanceTo(it.latitude, it.longitude) + it.accuracy <= scenario.region.radius
            }
        ) { "sparse route must remain inside the enclosing trigger circle" }

        scenario.processSynchronously(sparseRoute)

        waitUntil { scenario.id in scenario.store.getActivePolygonIds() }
        scenario.store.getEnteredIds() shouldBeEqualTo emptySet()
        scenario.transitions() shouldBeEqualTo emptyList()
    }

    @Test
    fun polygon_whenPoorFixesAreFollowedByAccurateFixes_thenWaitsBeforeEntering() {
        val scenario = prepareScenario(
            id = "accuracy-recovery",
            vertices = listOf(
                point(-120.0, -120.0),
                point(-120.0, 120.0),
                point(120.0, 120.0),
                point(120.0, -120.0)
            )
        )

        scenario.send(
            routeEndingBeforeNow(
                8_000_000_000L,
                RoutePoint(0.0, 0.0, accuracy = 250f),
                RoutePoint(0.0, 2.0, accuracy = 250f),
                RoutePoint(0.0, 4.0, accuracy = 250f)
            )
        )
        SystemClock.sleep(500)
        scenario.transitions() shouldBeEqualTo emptyList()

        scenario.send(
            route(
                RoutePoint(0.0, 0.0, accuracy = 5f),
                RoutePoint(0.0, 2.0, accuracy = 5f),
                RoutePoint(0.0, 4.0, accuracy = 5f)
            )
        )

        waitUntil { scenario.id in scenario.store.getEnteredIds() }
        scenario.transitions() shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun polygon_whenBatchContainsOutOfOrderAndDuplicateFixes_thenCountsOnlyUniqueTimeline() {
        val scenario = prepareScenario(
            id = "batched-square",
            vertices = listOf(
                point(-100.0, -100.0),
                point(-100.0, 100.0),
                point(100.0, 100.0),
                point(100.0, -100.0)
            )
        )
        val now = SystemClock.elapsedRealtimeNanos()
        val oldest = location(RoutePoint(0.0, 0.0), now - 4_000_000_000L)
        val middle = location(RoutePoint(0.0, 2.0), now - 2_000_000_000L)
        val newest = location(RoutePoint(0.0, 4.0), now)

        scenario.send(listOf(middle, oldest, Location(middle), newest))

        waitUntil { scenario.id in scenario.store.getEnteredIds() }
        scenario.transitions() shouldBeEqualTo listOf(Event.GeofenceTransition.ENTER)
    }

    @Test
    fun polygon_whenBackgroundBatchArrivesSeventySecondsLate_thenReconstructsObservedCrossing() {
        val circleVertices = List(24) { index ->
            val angle = 2.0 * PI * index / 24.0
            point(northMeters = 120.0 * sin(angle), eastMeters = 120.0 * cos(angle))
        }
        val scenario = prepareScenario("delayed-circle", circleVertices)

        scenario.send(
            routeEndingBeforeNow(
                70_000_000_000L,
                RoutePoint(0.0, -80.0, speed = 15f),
                RoutePoint(0.0, -50.0, speed = 15f),
                RoutePoint(0.0, -20.0, speed = 15f),
                RoutePoint(0.0, 140.0, speed = 15f),
                RoutePoint(0.0, 170.0, speed = 15f),
                RoutePoint(0.0, 200.0, speed = 15f)
            )
        )

        waitUntil { scenario.transitions().size == 2 }
        scenario.transitions() shouldBeEqualTo listOf(
            Event.GeofenceTransition.ENTER,
            Event.GeofenceTransition.EXIT
        )
    }

    private fun prepareScenario(id: String, vertices: List<PolygonCoordinate>): Scenario {
        val android = SDKComponent.android()
        val geometry = PolygonGeometry.from(vertices)
        val trigger = PolygonEnclosingCircle().calculate(geometry)
        val region = GeofenceRegion(
            id = id,
            latitude = trigger.center.latitude,
            longitude = trigger.center.longitude,
            radius = trigger.radiusMeters,
            polygonVertices = vertices
        )
        val userId = "route-user-$id"
        android.secureUserStore.saveUserId(userId)
        android.geofenceRegionStore.beginUserSession(userId)
        android.geofenceRegionStore.saveCachedRegions(listOf(region))
        android.geofenceRegionStore.saveRegisteredIds(setOf(id))
        android.geofenceRegionStore.saveRoutableRegisteredIds(setOf(id))
        return Scenario(
            id = id,
            userId = userId,
            generation = android.geofenceRegionStore.userStateGeneration(),
            region = region
        )
    }

    private fun route(vararg points: RoutePoint): List<Location> {
        return routeEndingBeforeNow(endOffsetNanos = 0L, points = points)
    }

    private fun routeEndingBeforeNow(
        endOffsetNanos: Long,
        vararg points: RoutePoint
    ): List<Location> {
        val end = SystemClock.elapsedRealtimeNanos() - endOffsetNanos
        val first = end - (points.lastIndex * FIX_INTERVAL_NANOS)
        return points.mapIndexed { index, point ->
            location(point, first + index * FIX_INTERVAL_NANOS)
        }
    }

    private fun location(point: RoutePoint, elapsedRealtimeNanos: Long) = Location("route-scenario").apply {
        val coordinate = point(point.northMeters, point.eastMeters)
        latitude = coordinate.latitude
        longitude = coordinate.longitude
        accuracy = point.accuracy
        speed = point.speed
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = System.currentTimeMillis()
    }

    private fun point(northMeters: Double, eastMeters: Double): PolygonCoordinate {
        val latitude = BASE_LATITUDE + northMeters / METERS_PER_LATITUDE_DEGREE
        val longitude = BASE_LONGITUDE +
            eastMeters / (METERS_PER_LATITUDE_DEGREE * cos(Math.toRadians(BASE_LATITUDE)))
        return PolygonCoordinate(latitude, longitude)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(40) {
            if (predicate()) return
            SystemClock.sleep(250)
        }
        check(predicate()) { "scenario did not reach the expected state within 10 seconds" }
    }

    private fun clearState() {
        if (!::context.isInitialized) return
        val android = SDKComponent.android()
        android.polygonGeofenceServiceController.stopAll()
        android.geofenceRegionStore.clearAll()
        android.pendingGeofenceDeliveryStore.removeAll()
        android.geofenceCooldownFilter.clearAll()
        android.secureUserStore.clearAll()
    }

    private inner class Scenario(
        val id: String,
        private val userId: String,
        private val generation: Long,
        val region: GeofenceRegion
    ) {
        val store = SDKComponent.android().geofenceRegionStore

        fun send(locations: List<Location>) {
            context.sendBroadcast(
                Intent(context, PolygonApproachReceiver::class.java)
                    .putExtra(PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION, generation)
                    .putExtra(
                        "com.google.android.gms.location.EXTRA_LOCATION_RESULT",
                        LocationResult.create(locations)
                    )
            )
        }

        fun processSynchronously(locations: List<Location>) {
            val accepted = runBlocking {
                SDKComponent.android().polygonGeofenceServiceController.processApproachLocations(
                    locations,
                    generation
                )
            }
            check(accepted) { "route was rejected by the current user session" }
        }

        fun transitions(): List<Event.GeofenceTransition> =
            Event.GeofenceTransition.entries
                .mapNotNull { transition ->
                    SDKComponent.android().geofenceCooldownStore
                        .getLastEmitTimestamp(userId, id, transition)
                        ?.let { timestamp -> timestamp to transition }
                }
                .sortedBy { it.first }
                .map { it.second }
    }

    private data class RoutePoint(
        val northMeters: Double,
        val eastMeters: Double,
        val accuracy: Float = 5f,
        val speed: Float = 0f
    )

    private companion object {
        const val BASE_LATITUDE = 37.775
        const val BASE_LONGITUDE = -122.4194
        const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        const val FIX_INTERVAL_NANOS = 2_000_000_000L
    }
}
