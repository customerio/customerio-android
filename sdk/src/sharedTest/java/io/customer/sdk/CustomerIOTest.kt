package io.customer.sdk

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.base.utils.ActionUtils.Companion.getErrorAction
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.di.overrideDependency
import io.customer.sdk.utils.BaseTest
import io.customer.sdk.utils.random
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class CustomerIOTest : BaseTest() {

    private val apiMock: CustomerIOApi = mock()

    private lateinit var customerIO: CustomerIO

    @Before
    fun setUp() {
        CustomerIOComponent.getInstance(siteId).apply {
            overrideDependency(CustomerIOApi::class.java, apiMock)
        }

        customerIO = CustomerIO(siteId)
    }

    @Test
    fun verifySDKConfigurationSetAfterBuild() {
        val givenSiteId = String.random
        val givenApiKey = String.random
        CustomerIO.Builder(
            siteId = givenSiteId,
            apiKey = givenApiKey,
            region = Region.EU,
            appContext = application
        ).setCustomerIOUrlHandler(object : CustomerIOUrlHandler {
            override fun handleCustomerIOUrl(uri: Uri): Boolean = false
        }).autoTrackScreenViews(true).build()

        val actual = CustomerIOComponent.getInstance(givenSiteId).sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.urlHandler.shouldNotBeNull()
        actual.autoTrackScreenViews shouldBeEqualTo true
    }

    @Test
    fun verifySDKReturnsSuccessWhenCustomerIsIdentified() {
        `when`(
            apiMock.identify(
                identifier = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.identify("test-identifier").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun verifySDKReturnsErrorWhenCustomerIdentifyRequestFails() {
        `when`(
            apiMock.identify(
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
    fun verifySDKReturnsSuccessWhenDeviceIsAdded() {
        `when`(
            apiMock.registerDeviceToken(
                any(),
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun verifySDKReturnsErrorWhenAddingDeviceRequestFails() {
        `when`(
            apiMock.registerDeviceToken(
                any(),
            )
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun verifySDKReturnsErrorWhenDeviceIsRemoved() {
        `when`(
            apiMock.deleteDeviceToken()
        ).thenReturn(getEmptyAction())

        val response = customerIO.deleteDeviceToken().execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun verifySDKReturnsErrorWhenRemovingDeviceRequestFails() {
        `when`(
            apiMock.deleteDeviceToken()
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.deleteDeviceToken().execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun verifySDKReturnsSuccessWhenPushEventMetricIsTracked() {
        `when`(
            apiMock.trackMetric(any(), any(), any())
        ).thenReturn(getEmptyAction())

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun verifySDKReturnsErrorWhenPushEventMetricRequestFails() {
        `when`(
            apiMock.trackMetric(any(), any(), any())
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifyError(response, StatusCode.BadRequest)
    }
}
