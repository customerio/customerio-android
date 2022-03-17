package io.customer.sdk

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.repository.TrackingRepository
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

internal class CustomerIOClientTest : BaseTest() {

    private val preferenceRepository: PreferenceRepository = mock()
    private val identityRepository: IdentityRepository = mock()
    private val trackingRepository: TrackingRepository = mock()
    private val pushNotificationRepository: PushNotificationRepository = mock()

    private lateinit var customerIOClient: CustomerIOClient

    @Before
    fun setup() {
        customerIOClient = CustomerIOClient(
            identityRepository = identityRepository,
            trackingRepository = trackingRepository,
            pushNotificationRepository = pushNotificationRepository,
            preferenceRepository = preferenceRepository
        )
    }

    @Test
    fun `verify client sends error when identify repo fails identifying`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(
                ActionUtils.getErrorAction(
                    errorResult = ErrorResult(
                        error = ErrorDetail(
                            statusCode = StatusCode.InternalServerError
                        )
                    )
                )
            )

        val result = customerIOClient.identify("identifier", mapOf()).execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `verify client sends success when identify repo succeed in identifying`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.identify("identifier", mapOf()).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer is identified then identifier is saved in prefs repo`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.identify("identifier", mapOf()).execute()

        verify(preferenceRepository, times(1)).saveIdentifier(any())
    }

    @Test
    fun `verify when customer identify is cleared its removed in prefs repo`() {
        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        customerIOClient.clearIdentify()

        verify(preferenceRepository, times(1)).removeIdentifier("identify")
    }

    @Test
    fun `verify client sends success when customer device is added`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(pushNotificationRepository.registerDeviceToken(any(), any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.registerDeviceToken(
            "token",
            deviceStore.buildDeviceAttributes()
        ).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer device is added then token is saved`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(
            pushNotificationRepository.registerDeviceToken(
                any(),
                any(),
                any()
            )
        )
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.registerDeviceToken("token", deviceStore.buildDeviceAttributes()).execute()

        verify(preferenceRepository, times(1)).saveDeviceToken("token")
    }

    @Test
    fun `verify when customer device is removed then token is removed`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(preferenceRepository.getDeviceToken()).thenReturn("token")

        `when`(
            pushNotificationRepository.deleteDeviceToken(any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.deleteDeviceToken().execute()

        verify(preferenceRepository, times(1)).removeDeviceToken("token")
    }

    @Test
    fun `verify client sends error when push repo fails in tracking push metric`() {
        `when`(
            pushNotificationRepository.trackMetric(any(), any(), any())
        ).thenReturn(
            ActionUtils.getErrorAction(
                errorResult = ErrorResult(
                    error = ErrorDetail(
                        statusCode = StatusCode.InternalServerError
                    )
                )
            )
        )

        val result =
            customerIOClient.trackMetric("delivery-id", MetricEvent.converted, "token").execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `verify client sends success when repo succeed in tracking push metric`() {
        `when`(
            pushNotificationRepository.trackMetric(any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        val result =
            customerIOClient.trackMetric("delivery-id", MetricEvent.converted, "token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify client sends error when tracking repo fails in tracking event`() {
        `when`(
            trackingRepository.track(any(), any(), any(), any())
        ).thenReturn(
            ActionUtils.getErrorAction(
                errorResult = ErrorResult(
                    error = ErrorDetail(
                        statusCode = StatusCode.BadRequest
                    )
                )
            )
        )

        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        val result = customerIOClient.track("name", mapOf("key" to "value")).execute()

        verifyError(result, StatusCode.BadRequest)
    }

    @Test
    fun `verify client sends success when tracking repo succeed in tracking event`() {
        `when`(
            trackingRepository.track(any(), any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        val result = customerIOClient.track("name", mapOf("key" to "value")).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify client sends success when tracking repo succeed in screen tracking`() {
        `when`(
            trackingRepository.track(any(), any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        val result = customerIOClient.screen("Home", emptyMap()).execute()

        verifySuccess(result, Unit)
    }
}
