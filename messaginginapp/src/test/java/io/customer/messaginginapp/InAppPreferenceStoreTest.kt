package io.customer.messaginginapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.store.InAppPreferenceStoreImpl
import io.customer.messaginginapp.testutils.core.IntegrationTest
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppPreferenceStoreTest : IntegrationTest() {

    private lateinit var inAppPreferenceStore: InAppPreferenceStoreImpl
    private lateinit var context: Context

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        context = ApplicationProvider.getApplicationContext()
        inAppPreferenceStore = InAppPreferenceStoreImpl(context)
    }

    override fun teardown() {
        inAppPreferenceStore.clearAll()

        super.teardown()
    }

    @Test
    fun saveNetworkResponse_givenUrlAndResponse_expectResponseSavedAndRetrieved() {
        val givenUrl = String.random
        val givenResponse = String.random

        inAppPreferenceStore.saveNetworkResponse(givenUrl, givenResponse)

        val retrievedResponse = inAppPreferenceStore.getNetworkResponse(givenUrl)
        givenResponse shouldBeEqualTo retrievedResponse
    }

    @Test
    fun getNetworkResponse_givenNonexistentUrl_expectNull() {
        val givenUrl = String.random

        val retrievedResponse = inAppPreferenceStore.getNetworkResponse(givenUrl)
        retrievedResponse shouldBe null
    }

    @Test
    fun clearAll_givenSavedResponses_expectAllResponsesCleared() {
        val givenUrl1 = String.random
        val givenResponse1 = String.random
        val givenUrl2 = String.random
        val givenResponse2 = String.random

        inAppPreferenceStore.saveNetworkResponse(givenUrl1, givenResponse1)
        inAppPreferenceStore.saveNetworkResponse(givenUrl2, givenResponse2)

        inAppPreferenceStore.clearAll()

        inAppPreferenceStore.getNetworkResponse(givenUrl1) shouldBe null
        inAppPreferenceStore.getNetworkResponse(givenUrl2) shouldBe null
    }

    @Test
    fun saveNetworkResponse_givenLongUrlAndResponse_expectSuccessSaveAndRetrieve() {
        val givenUrl = "https://very.long.url.com/" + "a".repeat(1000)
        val givenResponse = "Large response " + "data".repeat(1000)

        inAppPreferenceStore.saveNetworkResponse(givenUrl, givenResponse)

        val retrievedResponse = inAppPreferenceStore.getNetworkResponse(givenUrl)
        givenResponse shouldBeEqualTo retrievedResponse
    }

    @Test
    fun saveNetworkResponse_givenExistingUrl_expectResponseOverwritten() {
        val givenUrl = String.random
        val givenResponse1 = String.random
        val givenResponse2 = String.random

        inAppPreferenceStore.saveNetworkResponse(givenUrl, givenResponse1)
        inAppPreferenceStore.saveNetworkResponse(givenUrl, givenResponse2)

        val retrievedResponse = inAppPreferenceStore.getNetworkResponse(givenUrl)
        retrievedResponse shouldBeEqualTo givenResponse2
    }
}
