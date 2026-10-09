package io.customer.messagingpush.provider

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.SuccessContinuation
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.sdk.data.model.DeviceTokenType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class FirebaseTokenSourceTest : JUnit5Test() {

    private val legacyTokenTask = mockk<Task<String>>()
    private val tokenSuccess = slot<OnSuccessListener<in String>>()
    private val tokenFailure = slot<OnFailureListener>()
    private val mockFirebaseMessaging = mockk<FirebaseMessaging> {
        every { token } returns legacyTokenTask
    }
    private val installationIdTask = mockk<Task<String>>()
    private val mockFirebaseInstallations = mockk<FirebaseInstallations> {
        every { id } returns installationIdTask
    }
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)
    private val typedTask = mockk<Task<DeviceToken>>()
    private val typeContinuation = slot<SuccessContinuation<String, DeviceToken>>()

    private var registerCalls = 0

    private fun tokenSource(registration: Task<*>?) = FirebaseTokenSource(
        context = contextMock,
        firebaseMessaging = { mockFirebaseMessaging },
        firebaseInstallations = { mockFirebaseInstallations },
        pushLogger = mockPushLogger,
        register = {
            registerCalls++
            registration
        }
    )

    @Test
    fun fetchToken_givenNotOptedIn_expectLegacyTokenWithoutRegistering() {
        givenMetaData(optedIn = false)
        givenTyped(legacyTokenTask)

        val result = tokenSource(registration = mockk()).fetchToken()

        result shouldBeEqualTo typedTask
        assertTyped("legacy-token", DeviceTokenType.TOKEN)
        registerCalls shouldBeEqualTo 0
    }

    @Test
    fun fetchToken_givenNoMetaData_expectLegacyToken() {
        givenMetaData(null)
        givenTyped(legacyTokenTask)

        val result = tokenSource(registration = mockk()).fetchToken()

        result shouldBeEqualTo typedTask
        assertTyped("legacy-token", DeviceTokenType.TOKEN)
        registerCalls shouldBeEqualTo 0
    }

    @Test
    fun fetchToken_givenOptedInButRegisterUnavailable_expectLegacyTokenAndLog() {
        givenMetaData(optedIn = true)
        givenTokenListeners()

        val result = tokenSource(registration = null).fetchToken()
        tokenSuccess.captured.onSuccess("legacy-token")

        result shouldBeEqualTo typedTask
        assertTyped("legacy-token", DeviceTokenType.TOKEN)
        registerCalls shouldBeEqualTo 1
        assertCalledOnce { mockPushLogger.logInstallationIdUnsupported() }
        assertCalledNever { mockPushLogger.obtainingInstallationIdStarted() }
        assertCalledNever { mockPushLogger.logInstallationIdRegisterMissing(any()) }
    }

    @Test
    fun fetchToken_givenRegisterUnavailableButTokenDisabled_expectRegisterMissingError() {
        givenMetaData(optedIn = true)
        givenTokenListeners()
        val disabled = IllegalStateException("API disabled")

        tokenSource(registration = null).fetchToken()
        tokenFailure.captured.onFailure(disabled)

        assertCalledOnce { mockPushLogger.logInstallationIdRegisterMissing(disabled) }
        assertCalledNever { mockPushLogger.logInstallationIdUnsupported() }
    }

    @Test
    fun fetchToken_givenRegisterUnavailableAndTokenFailsOtherwise_expectNoRegisterMissingError() {
        givenMetaData(optedIn = true)
        givenTokenListeners()

        tokenSource(registration = null).fetchToken()
        tokenFailure.captured.onFailure(IOException("SERVICE_NOT_AVAILABLE"))

        assertCalledNever { mockPushLogger.logInstallationIdRegisterMissing(any()) }
    }

    @Test
    fun fetchToken_givenOptedIn_expectInstallationIdAfterRegistration() {
        givenMetaData(optedIn = true)
        val registration = mockk<Task<Any?>>()
        val chained = mockk<Task<String>>()
        val continuation = slot<SuccessContinuation<Any?, String>>()
        every { registration.onSuccessTask(capture(continuation)) } returns chained
        givenTyped(chained)

        val result = tokenSource(registration).fetchToken()

        result shouldBeEqualTo typedTask
        continuation.captured.then(null) shouldBeEqualTo installationIdTask
        assertTyped(INSTALLATION_ID, DeviceTokenType.FID)
        assertCalledOnce { mockPushLogger.obtainingInstallationIdStarted() }
        assertCalledNever { mockFirebaseMessaging.token }
    }

    // Unit tests compile against firebase-messaging 24.1.2, which has no register(): the case of an
    // app that opted in but still ships older Firebase.
    @Test
    fun fetchToken_givenOptedInOnFirebaseWithoutRegister_expectLegacyToken() {
        givenMetaData(optedIn = true)
        givenTokenListeners()
        val tokenSource = FirebaseTokenSource(
            context = contextMock,
            firebaseMessaging = { mockFirebaseMessaging },
            firebaseInstallations = { mockFirebaseInstallations },
            pushLogger = mockPushLogger
        )

        tokenSource.fetchToken() shouldBeEqualTo typedTask
        tokenSuccess.captured.onSuccess("legacy-token")
        assertTyped("legacy-token", DeviceTokenType.TOKEN)
        assertCalledOnce { mockPushLogger.logInstallationIdUnsupported() }
    }

    private fun givenTokenListeners() {
        every { legacyTokenTask.addOnSuccessListener(capture(tokenSuccess)) } returns legacyTokenTask
        every { legacyTokenTask.addOnFailureListener(capture(tokenFailure)) } returns legacyTokenTask
        givenTyped(legacyTokenTask)
    }

    private fun givenTyped(task: Task<String>) {
        every { task.onSuccessTask(capture(typeContinuation)) } returns typedTask
    }

    private fun assertTyped(value: String, type: DeviceTokenType) {
        typeContinuation.captured.then(value).result shouldBeEqualTo DeviceToken(value, type)
    }

    private fun givenMetaData(optedIn: Boolean) {
        givenMetaData(
            mockk<Bundle> {
                every { getBoolean("firebase_messaging_installation_id_enabled", false) } returns optedIn
            }
        )
    }

    private fun givenMetaData(metaData: Bundle?) {
        val packageManager = mockk<PackageManager>()
        every { contextMock.packageName } returns "io.customer.test"
        every { contextMock.packageManager } returns packageManager
        every {
            packageManager.getApplicationInfo("io.customer.test", PackageManager.GET_META_DATA)
        } returns mockk<ApplicationInfo>().also { it.metaData = metaData }
    }

    private companion object {
        const val INSTALLATION_ID = "cAbCdEfGhIjKlMnOpQrStU"
    }
}
