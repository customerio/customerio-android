package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.TaskCompletionSource
import io.customer.commontest.core.RobolectricTest
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticJournal
import io.customer.geofence.DwellDiagnosticRecorder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/** Exercises the actual GMS request and returned journal bytes, with GMS's task as the OS seam. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GmsPolygonFreshFixSourceTest : RobolectricTest() {
    @Test
    fun awaitFreshFix_givenCoarseProviderAnswer_expectUncachedHighAccuracyRequestAndActualQualityRecorded() = runTest {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        val task = TaskCompletionSource<Location>()
        val request = slot<CurrentLocationRequest>()
        val cancellation = slot<CancellationToken>()
        val client = mockk<FusedLocationProviderClient>()
        every { client.getCurrentLocation(capture(request), capture(cancellation)) } returns task.task
        val journal = DwellDiagnosticJournal(
            lazyOf(RuntimeEnvironment.getApplication().getSharedPreferences("gms-dwell-quality", Context.MODE_PRIVATE)),
            { true },
            StandardTestDispatcher(testScheduler)
        )
        val recorder = DwellDiagnosticRecorder(journal, { true }, backgroundScope)
        val source = GmsPolygonFreshFixSource(client, recorder)
        val answer = async { source.awaitFreshFix(3_000, PolygonFixPriority.HIGH_ACCURACY) }
        runCurrent()
        request.captured.priority shouldBeEqualTo Priority.PRIORITY_HIGH_ACCURACY
        request.captured.maxUpdateAgeMillis shouldBeEqualTo 0L
        request.captured.durationMillis shouldBeEqualTo 3_000L
        val coarse = Location("fused").apply {
            accuracy = 100f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 200_000_000
        }
        task.setResult(coarse)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        answer.await() shouldBeEqualTo coarse
        cancellation.captured.isCancellationRequested shouldBeEqualTo true
        val records = journal.snapshot().orEmpty()
        records.map { it.event } shouldBeEqualTo listOf(DwellDiagnosticEvent.FIX_REQUESTED, DwellDiagnosticEvent.FIX_RECEIVED)
        records.last().returnedAccuracyMeters shouldBeEqualTo 100.0
        records.last().provider shouldBeEqualTo "fused"
        records.last().fixAgeMs shouldBeEqualTo 200L
    }

    @Test
    fun awaitFreshFix_givenIdentityClearedWhileGmsWaits_expectLateAnswerReturnedButOldDiagnosticDropped() = runTest {
        val task = TaskCompletionSource<Location>()
        val client = mockk<FusedLocationProviderClient>()
        every { client.getCurrentLocation(any<CurrentLocationRequest>(), any<CancellationToken>()) } returns task.task
        val journal = DwellDiagnosticJournal(
            lazyOf(RuntimeEnvironment.getApplication().getSharedPreferences("gms-dwell-reset", Context.MODE_PRIVATE)),
            { true },
            StandardTestDispatcher(testScheduler)
        )
        val recorder = DwellDiagnosticRecorder(journal, { true }, backgroundScope)
        val source = GmsPolygonFreshFixSource(client, recorder)
        val answer = async { source.awaitFreshFix(3_000, PolygonFixPriority.HIGH_ACCURACY) }
        runCurrent()
        recorder.invalidateAndClear()
        task.setResult(Location("fused").apply { accuracy = 5f })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        answer.await()?.accuracy shouldBeEqualTo 5f
        advanceUntilIdle()
        journal.snapshot() shouldBeEqualTo emptyList()
    }
}
