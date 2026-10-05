package io.customer.geofence.replay

import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.pendingGeofenceDeliveryStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import java.io.File
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Synthetic scenarios exercise the same API, pipeline, clock and file outbox as recorded drives. */
@RunWith(RobolectricTestRunner::class)
class DwellReplayTest : ReplayTestSupport() {

    @Test
    fun replay_whenOutboxUnreadable_thenZeroCountCheckpointFails() = runTest {
        val store = SDKComponent.android().pendingGeofenceDeliveryStore
        val file = File(applicationMock.filesDir, PendingGeofenceDelivery.FILE_NAME)
        file.delete()
        assertTrue(file.mkdirs())
        try {
            val scenario = ScenarioLoader.load(scenarioFile(header("unreadable-outbox"), queued(0, "dwell", 0)))
            val result = runner().run(scenario)
            assertTrue("an unreadable outbox passed a zero-count assertion", result.assertionFailures.any { it.contains("unreadable") })
        } finally {
            file.delete()
            store.removeAll()
        }
    }

    @Test
    fun replay_whenLaterCheckpointHasEarlierTimestamp_thenPreservesRecordedOrder() = runTest {
        replay(
            callback(30, "enter"),
            queued(29, "enter", 1),
            callback(90, "dwell"),
            queued(90, "dwell", 1)
        )
    }

    @Test
    fun replay_whenDeadlinePasses_thenOnlyQualifiedNativeCallbackEmitsDwell() = runTest {
        replay(
            callback(30, "enter"),
            queued(30, "enter", 1),
            queued(89, "dwell", 0),
            callback(90, "dwell"),
            queued(90, "dwell", 1, "\"timestampAt\":90,\"enteredAtAt\":30,\"dwellThresholdSeconds\":60,\"dwellDurationSeconds\":60,\"hasVisitId\":true,\"detectionSource\":\"native\"")
        )
    }

    @Test
    fun replay_whenTimePassesWithoutNativeDwell_thenTimerDoesNotManufactureEvent() = runTest {
        replay(callback(30, "enter"), queued(30, "enter", 1), queued(600, "dwell", 0))
    }

    @Test
    fun replay_whenDuplicateDwellCallbacksArrive_thenOnlyOneQueuedEvent() = runTest {
        replay(
            callback(30, "enter"),
            callback(90, "dwell"),
            queued(90, "dwell", 1),
            callback(91, "dwell"),
            callback(150, "dwell"),
            queued(150, "dwell", 1, "\"timestampAt\":90,\"dwellDurationSeconds\":60")
        )
    }

    @Test
    fun replay_whenDwellHasNoAttributableFix_thenNoQueuedEvent() = runTest {
        replay(
            callback(30, "enter"),
            callback(90, "dwell", withFix = false),
            queued(90, "dwell", 0),
            callback(100, "dwell", age = 80),
            queued(100, "dwell", 0),
            callback(101, "dwell"),
            queued(101, "dwell", 1, "\"dwellDurationSeconds\":71")
        )
    }

    @Test
    fun replay_whenExitFollowsDwell_thenDurationAndVisitMatch() = runTest {
        val rows = replay(
            callback(30, "enter"),
            callback(90, "dwell"),
            callback(100, "exit", latitude = 10.0),
            queued(100, "exit", 1, "\"timestampAt\":100,\"enteredAtAt\":30,\"visitDurationSeconds\":70,\"hasVisitId\":true,\"dwellDurationSeconds\":null,\"detectionSource\":\"native\"")
        )
        val dwell = rows.single { it.transition == Event.GeofenceTransition.DWELL }
        val exit = rows.single { it.transition == Event.GeofenceTransition.EXIT }
        exit.visitId shouldBeEqualTo dwell.visitId
        exit.enteredAt shouldBeEqualTo dwell.enteredAt
    }

    @Test
    fun replay_whenLeaveBeforeThresholdThenReenter_thenTimeRestartsForNewVisit() = runTest {
        val rows = replay(
            callback(30, "enter"),
            callback(50, "exit", latitude = 10.0),
            queued(50, "dwell", 0),
            queued(50, "exit", 1, "\"visitDurationSeconds\":20"),
            callback(130, "enter"),
            queued(189, "dwell", 0),
            callback(190, "dwell"),
            queued(190, "dwell", 1, "\"enteredAtAt\":130,\"dwellDurationSeconds\":60")
        )
        val exit = rows.single { it.transition == Event.GeofenceTransition.EXIT }
        val dwell = rows.single { it.transition == Event.GeofenceTransition.DWELL }
        assertTrue("a new visit reused the previous visit ID", exit.visitId != dwell.visitId)
    }

    @Test
    fun replay_whenReenterAfterEmittedDwell_thenNewVisitCanDwellAgain() = runTest {
        val rows = replay(
            callback(30, "enter"),
            callback(90, "dwell"),
            callback(100, "exit", latitude = 10.0),
            callback(130, "enter"),
            callback(190, "dwell"),
            queued(190, "dwell", 2)
        )
        rows.filter { it.transition == Event.GeofenceTransition.DWELL }.map { it.visitId }.distinct().size shouldBeEqualTo 2
    }

    @Test
    fun replay_whenLegacyCatalogueOmitsThreshold_thenDwellRemainsDisabled() = runTest {
        replay(callback(30, "enter"), callback(100, "dwell"), queued(100, "dwell", 0), threshold = null)
        SDKComponent.android().geofenceRegionStore.getCachedRegion("B")?.dwellThresholdSeconds shouldBeEqualTo 0
    }

    @Test
    fun replay_whenFenceBelongsToTwoGeosets_thenFanoutSharesCommittedVisitFacts() = runTest {
        val rows = replay(
            callback(30, "enter"),
            callback(90, "dwell"),
            callback(91, "dwell"),
            queued(91, "dwell", 2),
            geosets = "[\"7\",\"8\"]"
        ).filter { it.transition == Event.GeofenceTransition.DWELL }
        rows.map { it.geosetId }.toSet() shouldBeEqualTo setOf("7", "8")
        rows.map { it.transitionId }.distinct().size shouldBeEqualTo 1
        rows.map { it.visitId }.distinct().size shouldBeEqualTo 1
        rows.map { it.toEventProperties().filterKeys { name -> name != "geosetId" } }.distinct().size shouldBeEqualTo 1
    }

    @Test
    fun replay_whenExitHasNoObservedEntry_thenUnknownDurationIsOmitted() = runTest {
        replay(
            // Without outside proof, ENTER can report existing presence rather than an arrival.
            callback(30, "enter"),
            callback(90, "dwell"),
            queued(90, "dwell", 1, "\"hasEnteredAt\":false,\"dwellDurationSeconds\":null"),
            callback(100, "exit", latitude = 10.0),
            queued(100, "exit", 1, "\"visitDurationSeconds\":null,\"hasVisitId\":false,\"hasEnteredAt\":false"),
            outsideProven = false
        )
    }

    @Test
    fun replay_whenNativeDwellPrecedesReceiptBasedDeadline_thenSubThresholdDurationIsOmitted() = runTest {
        replay(
            // GMS times dwell itself. ENTER delivery can trail the arrival, so a native dwell
            // before our receipt-based deadline qualifies without reporting a shorter duration.
            callback(30, "enter"),
            callback(89, "dwell"),
            queued(89, "dwell", 1, "\"hasVisitId\":true,\"enteredAtAt\":30,\"dwellDurationSeconds\":null"),
            callback(100, "exit", latitude = 10.0),
            queued(100, "exit", 1, "\"enteredAtAt\":30,\"visitDurationSeconds\":70")
        )
    }

    private suspend fun replay(
        vararg records: String,
        threshold: Long? = 60,
        geosets: String = "[\"7\"]",
        outsideProven: Boolean = true
    ): List<PendingGeofenceDelivery> {
        val dwell = threshold?.let { ",\"dwellThresholdSeconds\":$it" } ?: ""
        val fence = """{"id":"B","name":"Synthetic dwell","latitude":10.04510,"longitude":20.0,"radius":250,"transitionTypes":["enter","exit"],"geosetIds":$geosets$dwell}"""
        val scenario = ScenarioLoader.load(
            scenarioFile(
                header("synthetic-dwell", sourceKind = "authored"),
                """{"k":"when","at":1,"ev":"identity.changed","ok":true}""",
                """{"k":"when","at":1.5,"ev":"location.fix","lat":10.0,"lon":20.0,"prov":"bus"}""",
                """{"k":"given","at":2,"ev":"fixture.api.fetch","ok":true,"body":[$fence]}""",
                // A prior native EXIT proves outside even if its preceding ENTER was missed.
                // Registration coordinates alone cannot date a later ENTER as an arrival.
                *(if (outsideProven) arrayOf(callback(10, "exit", latitude = 10.0)) else emptyArray()),
                *records
            )
        )
        val result = runner().run(scenario)
        result.unsupported shouldBeEqualTo emptyList()
        result.unanswered shouldBeEqualTo emptyList()
        assertTrue(result.assertionFailures.joinToString("\n"), result.assertionFailures.isEmpty())
        api.fetchAccounting() shouldBeEqualTo null
        SDKComponent.android().geofenceRegionStore.getCachedRegion("B")?.dwellThresholdSeconds shouldBeEqualTo (threshold?.toInt() ?: 0)
        return SDKComponent.android().pendingGeofenceDeliveryStore.loadAll()
    }

    private fun callback(at: Int, transition: String, age: Int = 0, latitude: Double = 10.04510, withFix: Boolean = true): String {
        val fix = if (withFix) ",\"lat\":$latitude,\"lon\":20.0,\"acc\":10,\"age\":$age" else ""
        return """{"k":"when","at":$at,"ev":"os.callback","ids":"B","t":"$transition"$fix}"""
    }

    private fun queued(at: Int, transition: String, count: Int, properties: String = ""): String {
        val fields = properties.takeIf { it.isNotEmpty() }?.let { ",$it" } ?: ""
        return """{"k":"then","at":$at,"ev":"delivery.queued","id":"B","t":"$transition","count":$count$fields}"""
    }
}
