package io.customer.sdk

import android.net.Uri
import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.base.utils.ActionUtils.Companion.getErrorAction
import io.customer.sdk.MockCustomerIOBuilder.Companion.defaultConfig
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.verify

internal class CustomerIOTest : BaseTest() {

    private lateinit var customerIO: CustomerIO
    lateinit var mockCustomerIO: MockCustomerIOBuilder

    @Before
    fun setUp() {
        mockCustomerIO = MockCustomerIOBuilder()
        customerIO = mockCustomerIO.build()

        `when`(mockCustomerIO.store.deviceStore).thenReturn(deviceStore)
    }

    @Test
    fun `verify SDK default configuration is correct`() {
        customerIO.config.siteId shouldBeEqualTo MockCustomerIOBuilder.siteId
        customerIO.config.apiKey shouldBeEqualTo MockCustomerIOBuilder.apiKey
        customerIO.config.timeout shouldBeEqualTo MockCustomerIOBuilder.timeout.toLong()
        customerIO.config.region shouldBeEqualTo MockCustomerIOBuilder.region
        customerIO.config.urlHandler shouldBeEqualTo MockCustomerIOBuilder.urlHandler
        customerIO.config.autoTrackScreenViews shouldBeEqualTo MockCustomerIOBuilder.shouldAutoRecordScreenViews
        customerIO.config.autoTrackDeviceAttributes shouldBeEqualTo MockCustomerIOBuilder.autoTrackDeviceAttributes
    }

    @Test
    fun verify_onUpdatingBuilderConfigurations_expectCustomerIOOConfigToBeUpdated() {

        val mockCustomerIOBuilder =
            MockCustomerIOBuilder(
                defaultConfig.copy(
                    autoTrackDeviceAttributes = false,
                    autoTrackScreenViews = true,
                    siteId = "new-id",
                    apiKey = "new-key",
                    region = Region.EU,
                    urlHandler = object : CustomerIOUrlHandler {
                        override fun handleCustomerIOUrl(uri: Uri): Boolean {
                            return true
                        }
                    },
                    timeout = 9000
                )
            )
        customerIO = mockCustomerIOBuilder.build()

        customerIO.config.siteId shouldNotBeEqualTo defaultConfig.siteId
        customerIO.config.apiKey shouldNotBeEqualTo defaultConfig.apiKey
        customerIO.config.timeout shouldNotBeEqualTo defaultConfig.timeout
        customerIO.config.region shouldNotBeEqualTo defaultConfig.region
        customerIO.config.urlHandler shouldNotBeEqualTo defaultConfig.urlHandler
        customerIO.config.autoTrackScreenViews shouldNotBeEqualTo defaultConfig.autoTrackScreenViews
        customerIO.config.autoTrackDeviceAttributes shouldNotBeEqualTo defaultConfig.autoTrackDeviceAttributes
    }

    @Test
    fun `verify SDK returns success when customer is identified`() {
        `when`(
            mockCustomerIO.api.identify(
                identifier = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.identify("test-identifier").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when customer identify request fails`() {
        `when`(
            mockCustomerIO.api.identify(
                identifier = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.InternalServerError)
                )
            )
        )

        val response = customerIO.identify("test-identifier").execute()

        verifyError(response, StatusCode.InternalServerError)
    }

    @Test
    fun `verify SDK returns success when event is tracked`() {
        `when`(
            mockCustomerIO.api.track(
                name = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.track("event_name", mapOf("key" to "value")).execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns success when screen is tracked`() {
        `when`(
            mockCustomerIO.api.screen(
                name = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.screen("Login", mapOf("key" to "value")).execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when event tracking request fails`() {
        `when`(
            mockCustomerIO.api.track(
                name = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.InternalServerError)
                )
            )
        )

        val response = customerIO.track("event_name", mapOf("key" to "value")).execute()

        verifyError(response, StatusCode.InternalServerError)
    }

    @Test
    fun `verify SDK returns error when screen tracking request fails`() {
        `when`(
            mockCustomerIO.api.screen(
                name = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.BadRequest)
                )
            )
        )

        val response = customerIO.screen("Login", emptyMap()).execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns success when device is added`() {
        `when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
                any(),
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun verify_bothDefaultAndCustomAttributesGetsAdded_withRegisterToken() {

        `when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
                any(),
            )
        ).thenReturn(getEmptyAction())

        customerIO.deviceAttributes.putAll(mapOf("test" to "value"))

        val expectedToken = "token"

        val expectedAttributes = customerIO.deviceAttributes + deviceStore.buildDeviceAttributes()

        val response = customerIO.registerDeviceToken(expectedToken).execute()

        verify(mockCustomerIO.api).registerDeviceToken(expectedToken, expectedAttributes)

        verifySuccess(response, Unit)
    }

    @Test
    fun verify_defaultAttributesGetsSkipped_onBasisOfConfig() {

        val mockCustomerIO =
            MockCustomerIOBuilder(MockCustomerIOBuilder.defaultConfig.copy(autoTrackDeviceAttributes = false))
        customerIO = mockCustomerIO.build()

        `when`(mockCustomerIO.store.deviceStore).thenReturn(deviceStore)

        `when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
                any(),
            )
        ).thenReturn(getEmptyAction())

        customerIO.deviceAttributes.putAll(mapOf("test" to "value"))

        val expectedToken = "token"

        val expectedAttributes = mapOf("test" to "value")

        customerIO.registerDeviceToken(expectedToken).execute()

        verify(mockCustomerIO.api).registerDeviceToken(expectedToken, expectedAttributes)
    }

    @Test
    fun `verify SDK returns error when adding device request fails`() {
        `when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
                any(),
            )
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns error when device is removed`() {
        `when`(
            mockCustomerIO.api.deleteDeviceToken()
        ).thenReturn(getEmptyAction())

        val response = customerIO.deleteDeviceToken().execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when removing device request fails`() {
        `when`(
            mockCustomerIO.api.deleteDeviceToken()
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.deleteDeviceToken().execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns success when push event metric is tracked`() {
        `when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        ).thenReturn(getEmptyAction())

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when push event metric request fails`() {
        `when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifyError(response, StatusCode.BadRequest)
    }
}
