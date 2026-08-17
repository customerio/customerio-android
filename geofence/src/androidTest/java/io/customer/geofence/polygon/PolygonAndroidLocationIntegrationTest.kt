package io.customer.geofence.polygon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.location.LocationResult
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.store.PendingPolygonApproachBatch
import io.customer.geofence.store.PendingPolygonApproachLocation
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device/emulator, not Robolectric.
 *
 * Executed by the `module-instrumentation-test` job in `.github/workflows/test.yml`
 * (`./gradlew :geofence:connectedDebugAndroidTest`, API 31 on the `google_apis` image, so Play
 * services location is present). That job requires test results to exist, because
 * `assembleDebugAndroidTest` only proves this file compiles — which is not the same as these
 * assertions having run.
 */
@RunWith(AndroidJUnit4::class)
class PolygonAndroidLocationIntegrationTest {
    @get:Rule
    val locationPermissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

    private val fence = PolygonFence(
        id = "campus",
        geometry = PolygonGeometry.from(
            listOf(
                point(37.7745, -122.4200),
                point(37.7745, -122.4188),
                point(37.7755, -122.4188),
                point(37.7755, -122.4200)
            )
        )
    )

    @OptIn(InternalCustomerIOApi::class)
    @Test
    fun polygonApproachQueue_whenPersistedOnDevice_thenCoordinatesAreEncryptedAtRest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SDKComponent.setupAndroidComponent(context)
        val store = SDKComponent.android().geofenceRegionStore
        store.clearAll()
        store.beginUserSession("encrypted-queue-user")
        val batch = PendingPolygonApproachBatch(
            id = "encrypted-batch",
            userStateGeneration = store.userStateGeneration(),
            bootSessionId = "boot-test",
            locations = listOf(
                PendingPolygonApproachLocation(
                    latitude = 37.123456,
                    longitude = -122.654321,
                    accuracy = 5f,
                    speed = null,
                    timestampMillis = 1_000L,
                    elapsedRealtimeNanos = 2_000L
                )
            )
        )

        try {
            store.appendPendingPolygonApproachBatches(listOf(batch)) shouldBeEqualTo true
            store.getPendingPolygonApproachBatches() shouldBeEqualTo listOf(batch)
            val raw = context.getSharedPreferences(
                "io.customer.sdk.geofence_regions.${context.packageName}",
                Context.MODE_PRIVATE
            ).getString("pending_polygon_approach_batches", "").orEmpty()
            check(raw.isNotEmpty()) { "encrypted polygon approach queue was not persisted" }
            check(!raw.contains("37.123456") && !raw.contains("-122.654321")) {
                "polygon approach coordinates were stored in plaintext"
            }
        } finally {
            store.clearAll()
        }
    }

    @Test
    fun androidLocationBatch_whenFixesAreOrdered_thenEmitsPolygonEntry() {
        val processor = PolygonRouteProcessor()
        val states = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
        val baseElapsedRealtime = SystemClock.elapsedRealtimeNanos()

        val detections = (1L..3L).flatMap { sequence ->
            val fix = location(
                latitude = 37.7750,
                longitude = -122.4194,
                accuracyMeters = 5f,
                elapsedRealtimeNanos = baseElapsedRealtime + sequence
            ).toPolygonLocationFix() ?: error("complete Android location was rejected")
            processor.process(
                fences = listOf(fence),
                sample = fix.sample,
                elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                committedStates = states
            ).also { results ->
                results.forEach { states[it.polygonId] = PolygonCommittedState.INSIDE }
            }
        }

        detections shouldBeEqualTo listOf(
            PolygonTransitionDetection("campus", PolygonTransition.ENTER)
        )
    }

    @Test
    fun androidLocation_whenAccuracyIsMissing_thenRejectsFix() {
        val location = Location("gps").apply {
            latitude = 37.7750
            longitude = -122.4194
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        location.toPolygonLocationFix() shouldBeEqualTo null
    }

    @Test
    fun androidLocation_whenCoordinateIsInvalid_thenRejectsFix() {
        val location = location(
            latitude = 95.0,
            longitude = -122.4194,
            accuracyMeters = 5f,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )

        location.toPolygonLocationFix() shouldBeEqualTo null
    }

    @Test
    fun androidLocation_whenAccuracyIsZero_thenRejectsFix() {
        val location = location(
            latitude = 37.7750,
            longitude = -122.4194,
            accuracyMeters = 0f,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )

        location.toPolygonLocationFix() shouldBeEqualTo null
    }

    @Test
    fun emulatorLocationService_whenRouteIsInjected_thenFeedsPolygonProcessor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = "cio-polygon-integration"
        val locations = LinkedBlockingQueue<Location>()
        val listener = LocationListener { location -> locations.offer(location) }
        val callbackThread = HandlerThread("polygon-location-test").apply { start() }
        val locationWasEnabled = shell("cmd location is-location-enabled").trim().toBoolean()
        val priorMockLocationMode = shell("appops get 2000 android:mock_location")
            .substringAfterLast(':')
            .trim()
        var providerAdded = false

        try {
            shell("appops set 2000 android:mock_location allow")
            if (!locationWasEnabled) shell("cmd location set-location-enabled true")
            shell("cmd location providers add-test-provider $provider --supportsSpeed --powerRequirement 3")
            providerAdded = true
            shell("cmd location providers set-test-provider-enabled $provider true")
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                callbackThread.looper
            )

            val injectedLocations = listOf(
                37.7750 to -122.4196,
                37.7750 to -122.4194,
                37.7750 to -122.4192
            ).map { (latitude, longitude) ->
                shell(
                    "cmd location providers set-test-provider-location $provider " +
                        "--location $latitude,$longitude --accuracy 5"
                )
                locations.poll(5, TimeUnit.SECONDS)
                    ?: error("Android LocationManager did not deliver injected location")
            }

            val processor = PolygonRouteProcessor()
            val states = mutableMapOf("campus" to PolygonCommittedState.OUTSIDE)
            val detections = injectedLocations.flatMap { location ->
                val fix = location.toPolygonLocationFix()
                    ?: error("Android LocationManager delivered an invalid location")
                processor.process(
                    fences = listOf(fence),
                    sample = fix.sample,
                    elapsedRealtimeNanos = fix.elapsedRealtimeNanos,
                    committedStates = states
                ).also { results ->
                    results.forEach { states[it.polygonId] = PolygonCommittedState.INSIDE }
                }
            }

            detections shouldBeEqualTo listOf(
                PolygonTransitionDetection("campus", PolygonTransition.ENTER)
            )
        } finally {
            locationManager.removeUpdates(listener)
            if (providerAdded) shell("cmd location providers remove-test-provider $provider")
            if (!locationWasEnabled) shell("cmd location set-location-enabled false")
            shell("appops set 2000 android:mock_location $priorMockLocationMode")
            callbackThread.quitSafely()
        }
    }

    @OptIn(InternalCustomerIOApi::class)
    @Test
    fun polygonApproachReceiver_whenPassiveBatchIsInjected_thenCommitsPolygonEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SDKComponent.setupAndroidComponent(context)
        val android = SDKComponent.android()
        val store = android.geofenceRegionStore
        val pendingStore = android.pendingGeofenceDeliveryStore
        val region = GeofenceRegion(
            id = "approach-campus",
            latitude = 37.7750,
            longitude = -122.4194,
            radius = 1_200f,
            polygonVertices = fence.geometry.vertices
        )
        store.clearAll()
        pendingStore.removeAll()
        android.secureUserStore.saveUserId("approach-integration-user")
        store.beginUserSession("approach-integration-user")
        store.saveCachedRegions(listOf(region))
        store.saveRegisteredIds(setOf(region.id))
        store.saveRoutableRegisteredIds(setOf(region.id))
        val generation = store.userStateGeneration()
        val now = SystemClock.elapsedRealtimeNanos()
        val batch = listOf(
            location(37.7750, -122.4196, 5f, now - 4_000_000_000L),
            location(37.7750, -122.4194, 5f, now - 2_000_000_000L),
            location(37.7750, -122.4192, 5f, now)
        )

        try {
            context.sendBroadcast(
                Intent(context, PolygonApproachReceiver::class.java)
                    .putExtra(
                        PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION,
                        generation
                    )
                    .putExtra(
                        "com.google.android.gms.location.EXTRA_LOCATION_RESULT",
                        LocationResult.create(batch)
                    )
            )

            for (attempt in 0 until 40) {
                if (
                    region.id in store.getEnteredIds() &&
                    pendingStore.loadAll().any {
                        it.geofenceId == region.id && it.transition == Event.GeofenceTransition.ENTER
                    }
                ) {
                    break
                }
                SystemClock.sleep(250)
            }

            store.getEnteredIds() shouldBeEqualTo setOf(region.id)
            pendingStore.loadAll().single { it.geofenceId == region.id }.let { entry ->
                entry.transition shouldBeEqualTo Event.GeofenceTransition.ENTER
                entry.userId shouldBeEqualTo "approach-integration-user"
            }
        } finally {
            android.polygonGeofenceServiceController.stopAll()
            store.clearAll()
            pendingStore.removeAll()
            android.secureUserStore.clearAll()
        }
    }

    private fun location(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        elapsedRealtimeNanos: Long
    ) = Location("gps").apply {
        this.latitude = latitude
        this.longitude = longitude
        accuracy = accuracyMeters
        this.elapsedRealtimeNanos = elapsedRealtimeNanos
        time = System.currentTimeMillis()
    }

    private fun location(latitude: Double, longitude: Double) = location(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = 5f,
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    )

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude = latitude, longitude = longitude)

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        check(!output.contains("Exception occurred") && !output.startsWith("Error:")) {
            "Shell command failed: $command\n$output"
        }
        return output
    }
}
