package io.customer.messagingpush.util

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.random
import io.customer.sdk.repository.TrackRepository
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@RunWith(AndroidJUnit4::class)
class PushTrackingUtilTest : BaseTest() {

    private lateinit var util: PushTrackingUtil

    private val trackRepositoryMock: TrackRepository = mock()

    override fun setup() {
        super.setup()

        util = PushTrackingUtilImpl(trackRepositoryMock)
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithoutDeliveryData_expectDoNoTrackPush() {
        val givenBundle = Bundle().apply {
            putString("foo", String.random)
        }

        val result = util.parseLaunchedActivityForTracking(givenBundle)

        result.shouldBeFalse()

        verifyNoInteractions(trackRepositoryMock)
    }

    @Test
    fun parseLaunchedActivityForTracking_givenBundleWithDeliveryData_expectTrackPush() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenBundle = Bundle().apply {
            putString(PushTrackingUtil.DELIVERY_ID_KEY, givenDeliveryId)
            putString(PushTrackingUtil.DELIVERY_TOKEN_KEY, givenDeviceToken)
        }

        val result = util.parseLaunchedActivityForTracking(givenBundle)

        result.shouldBeTrue()

        verify(trackRepositoryMock).trackMetric(givenDeliveryId, MetricEvent.opened, givenDeviceToken)
    }
}
