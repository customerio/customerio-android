package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.Queue
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@RunWith(AndroidJUnit4::class)
class TrackRepositoryTest : BaseTest() {

    private val prefRepository: PreferenceRepository
        get() = di.sharedPreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()

    private lateinit var repository: TrackRepository

    @Before
    override fun setup() {
        super.setup()

        repository = TrackRepositoryImpl(
            preferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            logger = loggerMock
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
}
