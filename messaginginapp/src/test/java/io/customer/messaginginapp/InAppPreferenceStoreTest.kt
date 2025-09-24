package io.customer.messaginginapp

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

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)
        inAppPreferenceStore = InAppPreferenceStoreImpl(applicationMock)
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

    // Broadcast Message Tests
    @Test
    fun saveBroadcastMessages_givenJsonAndExpiry_expectSavedAndRetrieved() {
        val givenJson = """[{"queueId":"bc1","messageId":"msg1"}]"""
        val givenExpiry = System.currentTimeMillis() + 60000 // 1 minute

        inAppPreferenceStore.saveBroadcastMessages(givenJson, givenExpiry)

        val retrieved = inAppPreferenceStore.getBroadcastMessages()
        retrieved shouldBeEqualTo givenJson
        inAppPreferenceStore.isBroadcastMessagesExpired() shouldBe false
    }

    @Test
    fun getBroadcastMessages_givenExpiredData_expectNullAndAutoCleanup() {
        val givenJson = """[{"queueId":"bc1"}]"""
        val expiredTime = System.currentTimeMillis() - 1000 // 1 second ago

        inAppPreferenceStore.saveBroadcastMessages(givenJson, expiredTime)

        val retrieved = inAppPreferenceStore.getBroadcastMessages()
        retrieved shouldBe null
        inAppPreferenceStore.isBroadcastMessagesExpired() shouldBe false // Should be cleaned up
    }

    @Test
    fun incrementBroadcastTimesShown_givenMessageId_expectCorrectCounting() {
        val messageId = "test_broadcast"

        // Initial count should be 0
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 0

        // Increment and verify
        inAppPreferenceStore.incrementBroadcastTimesShown(messageId)
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 1

        inAppPreferenceStore.incrementBroadcastTimesShown(messageId)
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 2
    }

    @Test
    fun setBroadcastDismissed_givenVariousStates_expectCorrectStorage() {
        val messageId = "dismiss_test"

        // Initially not dismissed
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe false

        // Set dismissed
        inAppPreferenceStore.setBroadcastDismissed(messageId, true)
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe true

        // Unset dismissed
        inAppPreferenceStore.setBroadcastDismissed(messageId, false)
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe false
    }

    @Test
    fun setBroadcastIgnoreDismiss_givenVariousStates_expectCorrectStorage() {
        val messageId = "ignore_test"

        // Initially false (default value)
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe false

        // Set to true
        inAppPreferenceStore.setBroadcastIgnoreDismiss(messageId, true)
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe true

        // Set to false (stores false value)
        inAppPreferenceStore.setBroadcastIgnoreDismiss(messageId, false)
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe false
    }

    @Test
    fun clearBroadcastTracking_givenMessageWithAllData_expectCompleteCleanup() {
        val messageId = "cleanup_test"

        // Set up all types of tracking data
        inAppPreferenceStore.incrementBroadcastTimesShown(messageId)
        inAppPreferenceStore.setBroadcastDismissed(messageId, true)
        inAppPreferenceStore.setBroadcastIgnoreDismiss(messageId, true)

        // Verify data exists
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 1
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe true
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe true

        // Clear tracking
        inAppPreferenceStore.clearBroadcastTracking(messageId)

        // Verify all data cleared
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 0
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe false
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe false
    }

    @Test
    fun clearAllBroadcastData_givenStoredMessages_expectOnlyMessagesCleared() {
        val messageId = "partial_cleanup_test"
        val broadcastJson = """[{"queueId":"bc1"}]"""

        // Set up broadcast messages and tracking data
        inAppPreferenceStore.saveBroadcastMessages(broadcastJson, System.currentTimeMillis() + 60000)
        inAppPreferenceStore.incrementBroadcastTimesShown(messageId)
        inAppPreferenceStore.setBroadcastDismissed(messageId, true)

        // Clear only broadcast message storage
        inAppPreferenceStore.clearAllBroadcastData()

        // Verify messages cleared but tracking data preserved
        inAppPreferenceStore.getBroadcastMessages() shouldBe null
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 1 // Preserved
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe true // Preserved
    }

    @Test
    fun setBroadcastNextShowTime_givenFutureTime_expectDelayPeriodActive() {
        val messageId = "delay_test"
        val futureTime = System.currentTimeMillis() + 5000 // 5 seconds from now

        inAppPreferenceStore.setBroadcastNextShowTime(messageId, futureTime)

        inAppPreferenceStore.getBroadcastNextShowTime(messageId) shouldBeEqualTo futureTime
        inAppPreferenceStore.isBroadcastInDelayPeriod(messageId) shouldBe true
    }

    @Test
    fun isBroadcastInDelayPeriod_givenPastTime_expectDelayPeriodInactive() {
        val messageId = "delay_past_test"
        val pastTime = System.currentTimeMillis() - 1000 // 1 second ago

        inAppPreferenceStore.setBroadcastNextShowTime(messageId, pastTime)

        inAppPreferenceStore.isBroadcastInDelayPeriod(messageId) shouldBe false
    }

    @Test
    fun clearBroadcastTracking_givenMessageWithDelayData_expectCompleteCleanup() {
        val messageId = "delay_cleanup_test"

        // Set up all types of tracking data including delay
        inAppPreferenceStore.incrementBroadcastTimesShown(messageId)
        inAppPreferenceStore.setBroadcastDismissed(messageId, true)
        inAppPreferenceStore.setBroadcastIgnoreDismiss(messageId, true)
        inAppPreferenceStore.setBroadcastNextShowTime(messageId, System.currentTimeMillis() + 5000)

        // Verify data exists
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 1
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe true
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe true
        inAppPreferenceStore.isBroadcastInDelayPeriod(messageId) shouldBe true

        // Clear tracking
        inAppPreferenceStore.clearBroadcastTracking(messageId)

        // Verify all data cleared including delay
        inAppPreferenceStore.getBroadcastTimesShown(messageId) shouldBeEqualTo 0
        inAppPreferenceStore.isBroadcastDismissed(messageId) shouldBe false
        inAppPreferenceStore.getBroadcastIgnoreDismiss(messageId) shouldBe false
        inAppPreferenceStore.isBroadcastInDelayPeriod(messageId) shouldBe false
        inAppPreferenceStore.getBroadcastNextShowTime(messageId) shouldBeEqualTo 0
    }
}
