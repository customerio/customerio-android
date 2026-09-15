package io.customer.messagingpush.provider

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.logger.PushNotificationLogger
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class FCMTokenProviderTest : JUnit5Test() {

    private val mockContext = mockk<Context>()
    private val mockPackageManager = mockk<PackageManager>()
    private val mockGoogleApiAvailability = mockk<GoogleApiAvailability>()
    private val mockFirebaseMessaging = mockk<FirebaseMessaging>()
    private val mockFirebaseInstallations = mockk<FirebaseInstallations>()
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

    private val tokenProvider: DeviceTokenProvider = FCMTokenProviderImpl(
        mockContext,
        { mockGoogleApiAvailability },
        { mockFirebaseMessaging },
        { mockFirebaseInstallations },
        mockPushLogger
    )

    private fun mockInstallationIdRegistration(enabled: Boolean?) {
        val applicationInfo = mockk<ApplicationInfo>()
        applicationInfo.metaData = enabled?.let { value ->
            mockk<Bundle> {
                every { getBoolean(FCMTokenProviderImpl.METADATA_INSTALLATION_ID_ENABLED, false) } returns value
            }
        }
        every { mockContext.packageName } returns "io.customer.test"
        every { mockPackageManager.getApplicationInfo("io.customer.test", PackageManager.GET_META_DATA) } returns applicationInfo
        every { mockContext.packageManager } returns mockPackageManager
    }

    @Suppress("DEPRECATION")
    private fun mockRegistrationTokenTask(): Pair<Task<String>, CapturingSlot<OnCompleteListener<String>>> {
        val task = mockk<Task<String>>(relaxed = true)
        val taskSlot = slot<OnCompleteListener<String>>()
        every { mockFirebaseMessaging.getToken() } returns task
        every { task.addOnCompleteListener(capture(taskSlot)) } returns task
        return task to taskSlot
    }

    @Test
    fun test_getCurrentToken_givenPlayServicesAvailable_logSuccess() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = null)
        mockRegistrationTokenTask()

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
        mockInstallationIdRegistration(enabled = false)

        val token = "fcm-token"
        val (task, taskSlot) = mockRegistrationTokenTask()
        every { task.isSuccessful } returns true
        every { task.result } returns token

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
        mockInstallationIdRegistration(enabled = false)

        val exception = IllegalStateException()
        val (task, taskSlot) = mockRegistrationTokenTask()
        every { task.isSuccessful } returns false
        every { task.exception } returns exception

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        taskSlot.captured.onComplete(task)

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    @Suppress("DEPRECATION")
    fun test_getCurrentToken_givenObtainingTokenThrows() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = false)

        val exception = IllegalStateException()
        every { mockFirebaseMessaging.getToken() } throws exception

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    fun test_getCurrentToken_givenManifestFlagMissing_useRegistrationToken() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = null)

        val token = "fcm-token"
        val (task, taskSlot) = mockRegistrationTokenTask()
        every { task.isSuccessful } returns true
        every { task.result } returns token

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        taskSlot.captured.onComplete(task)

        assertCalledOnce { mockPushLogger.obtainingTokenSuccess(token) }
        result shouldBeEqualTo token
    }

    @Test
    fun test_getCurrentToken_givenInstallationIdEnabled_registerAndReturnInstallationId() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = true)

        val installationId = "firebase-installation-id"
        val registerTask = mockk<Task<Void>>(relaxed = true)
        val registerSlot = slot<OnCompleteListener<Void>>()
        every { registerTask.isSuccessful } returns true
        every { mockFirebaseMessaging.register() } returns registerTask
        every { registerTask.addOnCompleteListener(capture(registerSlot)) } returns registerTask

        val idTask = mockk<Task<String>>(relaxed = true)
        val idSlot = slot<OnCompleteListener<String>>()
        every { idTask.isSuccessful } returns true
        every { idTask.result } returns installationId
        every { mockFirebaseInstallations.id } returns idTask
        every { idTask.addOnCompleteListener(capture(idSlot)) } returns idTask

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        registerSlot.captured.onComplete(registerTask)
        idSlot.captured.onComplete(idTask)

        assertCalledOnce { mockPushLogger.obtainingTokenStarted() }
        assertCalledOnce { mockPushLogger.obtainingTokenSuccess(installationId) }
        result shouldBeEqualTo installationId
    }

    @Test
    fun test_getCurrentToken_givenInstallationIdEnabled_registerNotSuccessful() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = true)

        val exception = IllegalStateException()
        val registerTask = mockk<Task<Void>>(relaxed = true)
        val registerSlot = slot<OnCompleteListener<Void>>()
        every { registerTask.isSuccessful } returns false
        every { registerTask.exception } returns exception
        every { mockFirebaseMessaging.register() } returns registerTask
        every { registerTask.addOnCompleteListener(capture(registerSlot)) } returns registerTask

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        registerSlot.captured.onComplete(registerTask)

        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    fun test_getCurrentToken_givenInstallationIdEnabled_obtainingInstallationIdNotSuccessful() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = true)

        val registerTask = mockk<Task<Void>>(relaxed = true)
        val registerSlot = slot<OnCompleteListener<Void>>()
        every { registerTask.isSuccessful } returns true
        every { mockFirebaseMessaging.register() } returns registerTask
        every { registerTask.addOnCompleteListener(capture(registerSlot)) } returns registerTask

        val exception = IllegalStateException()
        val idTask = mockk<Task<String>>(relaxed = true)
        val idSlot = slot<OnCompleteListener<String>>()
        every { idTask.isSuccessful } returns false
        every { idTask.exception } returns exception
        every { mockFirebaseInstallations.id } returns idTask
        every { idTask.addOnCompleteListener(capture(idSlot)) } returns idTask

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        registerSlot.captured.onComplete(registerTask)
        idSlot.captured.onComplete(idTask)

        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    fun test_getCurrentToken_givenInstallationIdEnabled_registerThrows() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        mockInstallationIdRegistration(enabled = true)

        val exception = IllegalStateException()
        every { mockFirebaseMessaging.register() } throws exception

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }

        assertCalledOnce { mockPushLogger.obtainingTokenFailed(exception) }
        result shouldBeEqualTo null
    }

    @Test
    fun test_getCurrentToken_givenManifestLookupThrows_useRegistrationToken() {
        every { mockGoogleApiAvailability.isGooglePlayServicesAvailable(mockContext) } returns ConnectionResult.SUCCESS
        every { mockContext.packageName } returns "io.customer.test"
        every { mockContext.packageManager } returns mockPackageManager
        every { mockPackageManager.getApplicationInfo("io.customer.test", PackageManager.GET_META_DATA) } throws PackageManager.NameNotFoundException()

        val token = "fcm-token"
        val (task, taskSlot) = mockRegistrationTokenTask()
        every { task.isSuccessful } returns true
        every { task.result } returns token

        var result: String? = null
        tokenProvider.getCurrentToken { result = it }
        taskSlot.captured.onComplete(task)

        result shouldBeEqualTo token
    }
}
