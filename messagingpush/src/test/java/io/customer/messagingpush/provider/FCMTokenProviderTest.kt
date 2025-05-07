package io.customer.messagingpush.provider

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.logger.PushNotificationLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class FCMTokenProviderTest : JUnit5Test() {

    private val mockContext = mockk<Context>()
    private val mockGoogleApiAvailability = mockk<GoogleApiAvailability>()
    private val mockFirebaseMessaging = mockk<FirebaseMessaging>()
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

    private val tokenProvider: DeviceTokenProvider = FCMTokenProviderImpl(
        mockContext,
        { mockGoogleApiAvailability },
        { mockFirebaseMessaging },
        mockPushLogger
    )

    @Test
    fun test_getCurrentToken_givenPlayServicesAvailable_logSuccess() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS

        tokenProvider.getCurrentToken { }

        assertCalledOnce {
            mockPushLogger.logGooglePlayServicesAvailable()
        }
    }

    @Test
    fun test_getCurrentToken_givenPlayServicesUnavailable_logUnavailable() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.API_UNAVAILABLE

        tokenProvider.getCurrentToken { }

        assertCalledOnce {
            mockPushLogger.logGooglePlayServicesUnavailable(ConnectionResult.API_UNAVAILABLE)
        }
    }

    @Test
    fun test_getCurrentToken_givenPlayServicesCheckThrows_logUnavailable() {
        val illegalArgumentException = IllegalArgumentException()
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } throws illegalArgumentException

        tokenProvider.getCurrentToken { }

        assertCalledOnce {
            mockPushLogger.logGooglePlayServicesAvailabilityCheckFailed(illegalArgumentException)
        }
    }

    @Test
    fun test_getCurrentToken_givenObtainingTokenSuccessful() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS

        val token = "fcm-token"
        val task = mockk<Task<String>>(relaxed = true)
        val taskSlot = slot<OnCompleteListener<String>>()
        every { task.isSuccessful } returns true
        every { task.result } returns token
        every { mockFirebaseMessaging.getToken() } returns task
        every { task.addOnCompleteListener(capture(taskSlot)) } returns task

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        taskSlot.captured.onComplete(task)

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenSuccess(token) }
        result shouldBeEqualTo token
    }

    @Test
    fun test_getCurrentToken_givenObtainingTokenNotSuccessful() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS

        val task = mockk<Task<String>>(relaxed = true)
        val exception = IllegalStateException()
        val taskSlot = slot<OnCompleteListener<String>>()
        every { task.isSuccessful } returns false
        every { task.exception } returns exception
        every { mockFirebaseMessaging.getToken() } returns task
        every { task.addOnCompleteListener(capture(taskSlot)) } returns task

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        taskSlot.captured.onComplete(task)

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    fun test_getCurrentToken_givenObtainingTokenThrows() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS

        val exception = IllegalStateException()
        every { mockFirebaseMessaging.getToken() } throws exception

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }
}
