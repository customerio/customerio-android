package io.customer.messagingpush.provider

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.gms.tasks.SuccessContinuation
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import io.customer.commontest.core.JUnit5Test
import io.customer.commontest.extensions.assertCalledNever
import io.customer.commontest.extensions.assertCalledOnce
import io.customer.messagingpush.logger.PushNotificationLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class FirebaseTokenSourceTest : JUnit5Test() {

    private val legacyTokenTask = mockk<Task<String>>()
    private val mockFirebaseMessaging = mockk<FirebaseMessaging> {
        every { token } returns legacyTokenTask
    }
    private val installationIdTask = mockk<Task<String>>()
    private val mockFirebaseInstallations = mockk<FirebaseInstallations> {
        every { id } returns installationIdTask
    }
    private val mockPushLogger = mockk<PushNotificationLogger>(relaxed = true)

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

        val result = tokenSource(registration = mockk()).fetchToken()

        result shouldBeEqualTo legacyTokenTask
        registerCalls shouldBeEqualTo 0
    }

    @Test
    fun fetchToken_givenNoMetaData_expectLegacyToken() {
        givenMetaData(null)

        val result = tokenSource(registration = mockk()).fetchToken()

        result shouldBeEqualTo legacyTokenTask
        registerCalls shouldBeEqualTo 0
    }

    @Test
    fun fetchToken_givenOptedInButRegisterUnavailable_expectLegacyTokenAndLog() {
        givenMetaData(optedIn = true)

        val result = tokenSource(registration = null).fetchToken()

        result shouldBeEqualTo legacyTokenTask
        assertCalledOnce { mockPushLogger.logInstallationIdUnsupported() }
    }

    @Test
    fun fetchToken_givenOptedIn_expectInstallationIdAfterRegistration() {
        givenMetaData(optedIn = true)
        val registration = mockk<Task<Any?>>()
        val chained = mockk<Task<String>>()
        val continuation = slot<SuccessContinuation<Any?, String>>()
        every { registration.onSuccessTask(capture(continuation)) } returns chained

        val result = tokenSource(registration).fetchToken()

        result shouldBeEqualTo chained
        continuation.captured.then(null) shouldBeEqualTo installationIdTask
        assertCalledOnce { mockPushLogger.obtainingInstallationIdStarted() }
        assertCalledNever { mockFirebaseMessaging.token }
    }

    // Unit tests compile against firebase-messaging 24.1.2, which has no register(): the case of an
    // app that opted in but still ships older Firebase.
    @Test
    fun fetchToken_givenOptedInOnFirebaseWithoutRegister_expectLegacyToken() {
        givenMetaData(optedIn = true)
        val tokenSource = FirebaseTokenSource(
            context = contextMock,
            firebaseMessaging = { mockFirebaseMessaging },
            firebaseInstallations = { mockFirebaseInstallations },
            pushLogger = mockPushLogger
        )

        tokenSource.fetchToken() shouldBeEqualTo legacyTokenTask
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
}
