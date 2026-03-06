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
    fun saveAnonymousMessages_givenJsonAndExpiry_expectSavedAndRetrieved() {
        val givenJson = """[{"queueId":"bc1","messageId":"msg1"}]"""
        val givenExpiry = System.currentTimeMillis() + 60000 // 1 minute

        inAppPreferenceStore.saveAnonymousMessages(givenJson, givenExpiry)

        val retrieved = inAppPreferenceStore.getAnonymousMessages()
        retrieved shouldBeEqualTo givenJson
        inAppPreferenceStore.isAnonymousMessagesExpired() shouldBe false
    }

    @Test
    fun getAnonymousMessages_givenExpiredData_expectNullAndAutoCleanup() {
        val givenJson = """[{"queueId":"bc1"}]"""
        val expiredTime = System.currentTimeMillis() - 1000 // 1 second ago

        inAppPreferenceStore.saveAnonymousMessages(givenJson, expiredTime)

        val retrieved = inAppPreferenceStore.getAnonymousMessages()
        retrieved shouldBe null
        inAppPreferenceStore.isAnonymousMessagesExpired() shouldBe false // Should be cleaned up
    }

    @Test
    fun incrementAnonymousTimesShown_givenMessageId_expectCorrectCounting() {
        val messageId = "test_broadcast"

        // Initial count should be 0
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 0

        // Increment and verify
        inAppPreferenceStore.incrementAnonymousTimesShown(messageId)
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 1

        inAppPreferenceStore.incrementAnonymousTimesShown(messageId)
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 2
    }

    @Test
    fun setAnonymousDismissed_givenVariousStates_expectCorrectStorage() {
        val messageId = "dismiss_test"

        // Initially not dismissed
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe false

        // Set dismissed
        inAppPreferenceStore.setAnonymousDismissed(messageId, true)
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe true

        // Unset dismissed
        inAppPreferenceStore.setAnonymousDismissed(messageId, false)
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe false
    }

    @Test
    fun clearAnonymousTracking_givenMessageWithAllData_expectCompleteCleanup() {
        val messageId = "cleanup_test"

        // Set up tracking data
        inAppPreferenceStore.incrementAnonymousTimesShown(messageId)
        inAppPreferenceStore.setAnonymousDismissed(messageId, true)
        inAppPreferenceStore.setAnonymousNextShowTime(messageId, System.currentTimeMillis() + 60000)

        // Verify data exists
        val nextShowTime = inAppPreferenceStore.getAnonymousNextShowTime(messageId)
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 1
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe true
        (nextShowTime > 0) shouldBe true

        // Clear tracking
        inAppPreferenceStore.clearAnonymousTracking(messageId)

        // Verify all data cleared
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 0
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe false
        inAppPreferenceStore.getAnonymousNextShowTime(messageId) shouldBeEqualTo 0
    }

    @Test
    fun clearAllAnonymousData_givenStoredMessages_expectOnlyMessagesCleared() {
        val messageId = "partial_cleanup_test"
        val broadcastJson = """[{"queueId":"bc1"}]"""

        // Set up broadcast messages and tracking data
        inAppPreferenceStore.saveAnonymousMessages(broadcastJson, System.currentTimeMillis() + 60000)
        inAppPreferenceStore.incrementAnonymousTimesShown(messageId)
        inAppPreferenceStore.setAnonymousDismissed(messageId, true)

        // Clear only broadcast message storage
        inAppPreferenceStore.clearAllAnonymousData()

        // Verify messages cleared but tracking data preserved
        inAppPreferenceStore.getAnonymousMessages() shouldBe null
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 1 // Preserved
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe true // Preserved
    }

    @Test
    fun setAnonymousNextShowTime_givenFutureTime_expectDelayPeriodActive() {
        val messageId = "delay_test"
        val futureTime = System.currentTimeMillis() + 5000 // 5 seconds from now

        inAppPreferenceStore.setAnonymousNextShowTime(messageId, futureTime)

        inAppPreferenceStore.getAnonymousNextShowTime(messageId) shouldBeEqualTo futureTime
        inAppPreferenceStore.isAnonymousInDelayPeriod(messageId) shouldBe true
    }

    @Test
    fun isAnonymousInDelayPeriod_givenPastTime_expectDelayPeriodInactive() {
        val messageId = "delay_past_test"
        val pastTime = System.currentTimeMillis() - 1000 // 1 second ago

        inAppPreferenceStore.setAnonymousNextShowTime(messageId, pastTime)

        inAppPreferenceStore.isAnonymousInDelayPeriod(messageId) shouldBe false
    }

    @Test
    fun clearAnonymousTracking_givenMessageWithDelayData_expectCompleteCleanup() {
        val messageId = "delay_cleanup_test"

        // Set up all types of tracking data including delay
        inAppPreferenceStore.incrementAnonymousTimesShown(messageId)
        inAppPreferenceStore.setAnonymousDismissed(messageId, true)
        inAppPreferenceStore.setAnonymousNextShowTime(messageId, System.currentTimeMillis() + 5000)

        // Verify data exists
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 1
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe true
        inAppPreferenceStore.isAnonymousInDelayPeriod(messageId) shouldBe true

        // Clear tracking
        inAppPreferenceStore.clearAnonymousTracking(messageId)

        // Verify all data cleared including delay
        inAppPreferenceStore.getAnonymousTimesShown(messageId) shouldBeEqualTo 0
        inAppPreferenceStore.isAnonymousDismissed(messageId) shouldBe false
        inAppPreferenceStore.isAnonymousInDelayPeriod(messageId) shouldBe false
        inAppPreferenceStore.getAnonymousNextShowTime(messageId) shouldBeEqualTo 0
    }

    // Inbox Message Opened Status Tests
    @Test
    fun saveInboxMessageOpenedStatus_givenQueueIdAndStatus_expectSavedAndRetrieved() {
        val queueId = "queue-123"

        // Initially should return null (not cached)
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId) shouldBe null

        // Save as opened
        inAppPreferenceStore.saveInboxMessageOpenedStatus(queueId, true)
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId) shouldBeEqualTo true

        // Save as unopened
        inAppPreferenceStore.saveInboxMessageOpenedStatus(queueId, false)
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId) shouldBeEqualTo false
    }

    @Test
    fun getInboxMessageOpenedStatus_givenNonexistentQueueId_expectNull() {
        val queueId = "nonexistent-queue"

        val status = inAppPreferenceStore.getInboxMessageOpenedStatus(queueId)
        status shouldBe null
    }

    @Test
    fun clearInboxMessageOpenedStatus_givenCachedStatus_expectCleared() {
        val queueId = "queue-456"

        // Save status
        inAppPreferenceStore.saveInboxMessageOpenedStatus(queueId, true)
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId) shouldBeEqualTo true

        // Clear status
        inAppPreferenceStore.clearInboxMessageOpenedStatus(queueId)
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId) shouldBe null
    }

    @Test
    fun saveInboxMessageOpenedStatus_givenMultipleMessages_expectEachTrackedIndependently() {
        val queueId1 = "queue-1"
        val queueId2 = "queue-2"
        val queueId3 = "queue-3"

        // Save different statuses for different messages
        inAppPreferenceStore.saveInboxMessageOpenedStatus(queueId1, true)
        inAppPreferenceStore.saveInboxMessageOpenedStatus(queueId2, false)
        // Don't save anything for queueId3

        // Verify each is tracked correctly
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId1) shouldBeEqualTo true
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId2) shouldBeEqualTo false
        inAppPreferenceStore.getInboxMessageOpenedStatus(queueId3) shouldBe null
    }
}
