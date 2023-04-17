package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.DeliveryPayload
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.util.Logger
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*

@RunWith(AndroidJUnit4::class)
class TrackRepositoryTest : BaseTest() {

    private val prefRepository: SitePreferenceRepository
        get() = di.sitePreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()

    private lateinit var repository: TrackRepository
    private val hooksManager: HooksManager = mock()

    @Before
    override fun setup() {
        super.setup()

        repository = TrackRepositoryImpl(
            sitePreferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            logger = loggerMock,
            hooksManager = hooksManager
        )
    }

    // track

    @Test
    fun track_givenNoProfileIdentified_expectDoNotAddTaskBackgroundQueue() {
        repository.track(String.random, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun track_givenProfileIdentified_expectAddTaskBackgroundQueue() {
        val givenIdentifier = String.random
        val givenTrackEventName = String.random
        val givenAttributes = mapOf("foo" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)

        whenever(
            backgroundQueueMock.queueTrack(
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.track(givenTrackEventName, givenAttributes)

        verify(backgroundQueueMock).queueTrack(
            givenIdentifier,
            givenTrackEventName,
            EventType.event,
            givenAttributes
        )
    }

    // trackMetric

    @Test
    fun trackMetric_expectAddEventToBackgroundQueue() {
        val givenDeliveryId = String.random
        val givenEvent = MetricEvent.opened
        val givenDeviceToken = String.random

        repository.trackMetric(givenDeliveryId, givenEvent, givenDeviceToken)

        verify(backgroundQueueMock).queueTrackMetric(
            givenDeliveryId,
            givenDeviceToken,
            givenEvent
        )
    }

    // trackInAppMetric

    fun trackInAppMetric_expectAddEventToBackgroundQueue() {
        val givenDeliveryId = String.random
        val givenEvent = MetricEvent.opened
        val givenMetadata = DeliveryPayload.Metadata(String.random, String.random)

        repository.trackInAppMetric(givenDeliveryId, givenEvent, givenMetadata)

        verify(backgroundQueueMock).queueTrackInAppMetric(
            givenDeliveryId,
            givenEvent,
            givenMetadata
        )
    }
}
