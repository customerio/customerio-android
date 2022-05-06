package io.customer.sdk

import android.net.Uri
import io.customer.common_test.BaseTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.repository.CleanupRepository
import io.customer.sdk.utils.random
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class CustomerIOTest : BaseTest() {

    private val apiMock: CustomerIOApi = mock()
    private val cleanupRepositoryMock: CleanupRepository = mock()

    private lateinit var customerIO: CustomerIO

    @Before
    fun setUp() {
        super.setup()

        di.overrideDependency(CustomerIOApi::class.java, apiMock)
        di.overrideDependency(CleanupRepository::class.java, cleanupRepositoryMock)

        customerIO = CustomerIO(di)
    }

    @Test
    fun verifySDKConfigurationSetAfterBuild() {
        val givenSiteId = String.random
        val givenApiKey = String.random
        val client = CustomerIO.Builder(
            siteId = givenSiteId,
            apiKey = givenApiKey,
            region = Region.EU,
            appContext = application
        ).setCustomerIOUrlHandler(object : CustomerIOUrlHandler {
            override fun handleCustomerIOUrl(uri: Uri): Boolean = false
        }).autoTrackScreenViews(true).build()

        val actual = client.diGraph.sdkConfig

        actual.siteId shouldBeEqualTo givenSiteId
        actual.apiKey shouldBeEqualTo givenApiKey
        actual.timeout.shouldNotBeNull()
        actual.region shouldBeEqualTo Region.EU
        actual.urlHandler.shouldNotBeNull()
        actual.autoTrackScreenViews shouldBeEqualTo true
    }

    @Test
    fun initializeSdk_expectRunCleanup(): Unit = runBlocking {
        getRandomCustomerIOBuilder().build()

        verify(cleanupRepositoryMock).cleanup()
    }

    private fun getRandomCustomerIOBuilder(): CustomerIO.Builder = CustomerIO.Builder(
        siteId = String.random,
        apiKey = String.random,
        region = Region.EU,
        appContext = application
    ).apply {
        this.overrideDiGraph = di
    }
}
