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
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

internal class CustomerIOClientTest {

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
        Mockito.`when`(identityRepository.identify(any(), any()))
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
        Mockito.`when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.identify("identifier", mapOf()).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer is identified then identifier is saved in prefs repo`() {
        Mockito.`when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.identify("identifier", mapOf()).execute()

        verify(preferenceRepository, times(1)).saveIdentifier(any())
    }

    @Test
    fun `verify when customer identify is cleared its removed in prefs repo`() {
        Mockito.`when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        customerIOClient.clearIdentify()

        verify(preferenceRepository, times(1)).removeIdentifier("identify")
    }

    @Test
    fun `verify client sends success when customer device is added`() {
        Mockito.`when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        Mockito.`when`(pushNotificationRepository.registerDeviceToken(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.registerDeviceToken("token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer device is added then token is saved`() {
        Mockito.`when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        Mockito.`when`(pushNotificationRepository.registerDeviceToken(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.registerDeviceToken("token").execute()

        verify(preferenceRepository, times(1)).saveDeviceToken("token")
    }

    @Test
    fun `verify when customer device is removed then token is removed`() {
        Mockito.`when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        Mockito.`when`(preferenceRepository.getDeviceToken()).thenReturn("token")

        Mockito.`when`(
            pushNotificationRepository.deleteDeviceToken(any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.deleteDeviceToken().execute()

        verify(preferenceRepository, times(1)).removeDeviceToken("token")
    }

    @Test
    fun `verify client sends error when push repo fails in tracking push metric`() {
        Mockito.`when`(
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
        Mockito.`when`(
            pushNotificationRepository.trackMetric(any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        val result =
            customerIOClient.trackMetric("delivery-id", MetricEvent.converted, "token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify client sends error when tracking repo fails in tracking event`() {
        Mockito.`when`(
            trackingRepository.track(any(), any(), any())
        ).thenReturn(
            ActionUtils.getErrorAction(
                errorResult = ErrorResult(
                    error = ErrorDetail(
                        statusCode = StatusCode.BadRequest
                    )
                )
            )
        )

        Mockito.`when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        val result = customerIOClient.track("name", mapOf("key" to "value")).execute()

        verifyError(result, StatusCode.BadRequest)
    }

    @Test
    fun `verify client sends success when tracking repo succeed in tracking event`() {
        Mockito.`when`(
            trackingRepository.track(any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        Mockito.`when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        val result = customerIOClient.track("name", mapOf("key" to "value")).execute()

        verifySuccess(result, Unit)
    }
}
