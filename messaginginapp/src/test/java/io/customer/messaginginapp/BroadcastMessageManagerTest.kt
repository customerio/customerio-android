package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.BroadcastMessageManagerImpl
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotContain
import org.junit.jupiter.api.Test

class BroadcastMessageManagerTest : JUnitTest() {

    private lateinit var broadcastManager: BroadcastMessageManagerImpl
    private lateinit var mockPreferenceStore: InAppPreferenceStore
    private lateinit var state: InAppMessagingState

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(mockk<InAppPreferenceStore>(relaxed = true))
                    }
                }
            }
        )

        mockPreferenceStore = SDKComponent.inAppPreferenceStore

        // Configure mock behavior to act like a real preference store
        val broadcastTimesShownMap = mutableMapOf<String, Int>()
        val broadcastDismissedMap = mutableMapOf<String, Boolean>()
        val broadcastIgnoreDismissMap = mutableMapOf<String, Boolean>()
        val broadcastNextShowTimeMap = mutableMapOf<String, Long>()
        var broadcastMessagesData: String? = null
        var broadcastMessagesExpiry: Long = 0

        every { mockPreferenceStore.getBroadcastTimesShown(any()) } answers { broadcastTimesShownMap[firstArg()] ?: 0 }
        every { mockPreferenceStore.incrementBroadcastTimesShown(any()) } answers {
            val messageId = firstArg<String>()
            broadcastTimesShownMap[messageId] = (broadcastTimesShownMap[messageId] ?: 0) + 1
        }
        every { mockPreferenceStore.isBroadcastDismissed(any()) } answers { broadcastDismissedMap[firstArg()] ?: false }
        every { mockPreferenceStore.setBroadcastDismissed(any(), any()) } answers {
            val messageId = firstArg<String>()
            val dismissed = secondArg<Boolean>()
            if (dismissed) broadcastDismissedMap[messageId] = true else broadcastDismissedMap.remove(messageId)
        }
        every { mockPreferenceStore.getBroadcastIgnoreDismiss(any()) } answers { broadcastIgnoreDismissMap[firstArg()] ?: false }
        every { mockPreferenceStore.setBroadcastIgnoreDismiss(any(), any()) } answers {
            val messageId = firstArg<String>()
            val ignoreDismiss = secondArg<Boolean>()
            broadcastIgnoreDismissMap[messageId] = ignoreDismiss
        }
        every { mockPreferenceStore.saveBroadcastMessages(any(), any()) } answers {
            broadcastMessagesData = firstArg()
            broadcastMessagesExpiry = secondArg()
        }
        every { mockPreferenceStore.getBroadcastMessages() } answers {
            if (System.currentTimeMillis() > broadcastMessagesExpiry) {
                broadcastMessagesData = null
                broadcastMessagesExpiry = 0
            }
            broadcastMessagesData
        }
        every { mockPreferenceStore.clearBroadcastTracking(any()) } answers {
            val messageId = firstArg<String>()
            broadcastTimesShownMap.remove(messageId)
            broadcastDismissedMap.remove(messageId)
            broadcastIgnoreDismissMap.remove(messageId)
            broadcastNextShowTimeMap.remove(messageId)
        }
        every { mockPreferenceStore.clearAllBroadcastData() } answers {
            broadcastMessagesData = null
            broadcastMessagesExpiry = 0
        }
        every { mockPreferenceStore.setBroadcastNextShowTime(any(), any()) } answers {
            val messageId = firstArg<String>()
            val nextShowTime = secondArg<Long>()
            broadcastNextShowTimeMap[messageId] = nextShowTime
        }
        every { mockPreferenceStore.getBroadcastNextShowTime(any()) } answers { broadcastNextShowTimeMap[firstArg()] ?: 0 }
        every { mockPreferenceStore.isBroadcastInDelayPeriod(any()) } answers {
            val messageId = firstArg<String>()
            val nextShowTime = broadcastNextShowTimeMap[messageId] ?: 0
            nextShowTime > 0 && System.currentTimeMillis() < nextShowTime
        }

        state = InAppMessagingState(userId = "testuser123")
        broadcastManager = BroadcastMessageManagerImpl(state)
    }

    @Test
    fun updateBroadcastsLocalStore_givenSingleUseMessage_expectStoredAndEligibleOnce() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc1",
            count = 1,
            delay = 0,
            ignoreDismiss = false
        )
        val givenRegularMessage = createRegularMessage("reg1")

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast, givenRegularMessage))

        val eligibleFirst = broadcastManager.getEligibleBroadcasts()
        eligibleFirst.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
        eligibleFirst.any { it.queueId == givenRegularMessage.queueId } shouldBe false

        broadcastManager.markBroadcastAsSeen("bc1")

        val eligibleAfterSeen = broadcastManager.getEligibleBroadcasts()
        eligibleAfterSeen.shouldBeEmpty()
    }

    @Test
    fun getEligibleBroadcasts_givenUnlimitedMessage_expectAlwaysEligible() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_unlimited",
            count = 0, // Unlimited
            delay = 0,
            ignoreDismiss = false
        )

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        repeat(5) {
            val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
            eligibleBroadcasts.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
            broadcastManager.markBroadcastAsSeen("bc_unlimited")
        }

        val finalEligible = broadcastManager.getEligibleBroadcasts()
        finalEligible.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
    }

    @Test
    fun markBroadcastAsDismissed_givenIgnoreDismissTrue_expectStillEligible() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_ignore_dismiss",
            count = 0,
            delay = 0,
            ignoreDismiss = true
        )

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))
        broadcastManager.markBroadcastAsDismissed("bc_ignore_dismiss")

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
        eligibleBroadcasts.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
        mockPreferenceStore.isBroadcastDismissed("bc_ignore_dismiss") shouldBe false
    }

    @Test
    fun markBroadcastAsDismissed_givenIgnoreDismissFalse_expectNotEligible() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_respect_dismiss",
            count = 0,
            delay = 0,
            ignoreDismiss = false
        )

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))
        broadcastManager.markBroadcastAsDismissed("bc_respect_dismiss")

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
        eligibleBroadcasts shouldNotContain givenBroadcast
        mockPreferenceStore.isBroadcastDismissed("bc_respect_dismiss") shouldBe true
    }

    @Test
    fun updateBroadcastsLocalStore_givenMessageRemovedFromServer_expectCleanup() {
        val givenBroadcast1 = createBroadcastMessage("bc1", count = 3)
        val givenBroadcast2 = createBroadcastMessage("bc2", count = 3)

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1, givenBroadcast2))
        broadcastManager.markBroadcastAsSeen("bc1")
        broadcastManager.markBroadcastAsSeen("bc2")
        mockPreferenceStore.getBroadcastTimesShown("bc1") shouldBeEqualTo 1
        mockPreferenceStore.getBroadcastTimesShown("bc2") shouldBeEqualTo 1

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1))

        mockPreferenceStore.getBroadcastTimesShown("bc1") shouldBeEqualTo 1
        mockPreferenceStore.getBroadcastTimesShown("bc2") shouldBeEqualTo 0
        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
        eligibleBroadcasts.map { it.queueId } shouldBeEqualTo listOf("bc1")
    }

    @Test
    fun updateBroadcastsLocalStore_givenNoMessageFromServer_expectCompleteCleanup() {
        val givenBroadcast = createBroadcastMessage("bc_expire", count = 5)
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))
        broadcastManager.markBroadcastAsSeen("bc_expire")
        broadcastManager.getEligibleBroadcasts().shouldHaveSize(1)
        mockPreferenceStore.getBroadcastTimesShown("bc_expire") shouldBeEqualTo 1

        broadcastManager.updateBroadcastsLocalStore(emptyList())

        broadcastManager.getEligibleBroadcasts().shouldBeEmpty()
        mockPreferenceStore.getBroadcastMessages() shouldBe null
    }

    @Test
    fun getEligibleBroadcasts_calledMultipleTimes_expectCacheOptimization() {
        val givenBroadcast = createBroadcastMessage("bc_cache", count = 3)
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        val result1 = broadcastManager.getEligibleBroadcasts()
        val result2 = broadcastManager.getEligibleBroadcasts()
        val result3 = broadcastManager.getEligibleBroadcasts()

        result1 shouldBeEqualTo result2
        result2 shouldBeEqualTo result3
        result1.shouldHaveSize(1)
    }

    @Test
    fun getEligibleBroadcasts_givenMessageWithoutQueueId_expectFilteredOut() {
        val givenInvalidBroadcast = createBroadcastMessage("bc_valid", count = 1).copy(queueId = null)
        val givenValidBroadcast = createBroadcastMessage("bc_invalid", count = 1)

        broadcastManager.updateBroadcastsLocalStore(listOf(givenInvalidBroadcast, givenValidBroadcast))

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()

        eligibleBroadcasts.size shouldBeEqualTo 1
        eligibleBroadcasts.first().queueId shouldBeEqualTo "bc_invalid"
    }

    @Test
    fun updateBroadcastsLocalStore_givenMixedMessages_expectOnlyBroadcastsProcessed() {
        val givenBroadcast1 = createBroadcastMessage("bc1", count = 2)
        val givenRegular1 = createRegularMessage("reg1")
        val givenBroadcast2 = createBroadcastMessage("bc2", count = 1)
        val givenRegular2 = createRegularMessage("reg2")

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1, givenRegular1, givenBroadcast2, givenRegular2))

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
        eligibleBroadcasts.size shouldBeEqualTo 2
        eligibleBroadcasts.map { it.queueId } shouldBeEqualTo listOf("bc1", "bc2")
    }

    @Test
    fun updateBroadcastsLocalStore_givenNoUserToken_expectNoOperations() {
        val givenStateNoUser = InAppMessagingState(userId = null, anonymousId = null)
        val givenManagerNoUser = BroadcastMessageManagerImpl(givenStateNoUser)
        val givenBroadcast = createBroadcastMessage("bc_no_user", count = 1)

        givenManagerNoUser.updateBroadcastsLocalStore(listOf(givenBroadcast))

        givenManagerNoUser.getEligibleBroadcasts().shouldBeEmpty()
        mockPreferenceStore.getBroadcastMessages() shouldBe null
    }

    @Test
    fun markBroadcastAsSeen_givenMultipleCalls_expectAccurateCountTracking() {
        val givenBroadcast = createBroadcastMessage("bc_count", count = 3)
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))
        mockPreferenceStore.getBroadcastTimesShown("bc_count") shouldBeEqualTo 0

        broadcastManager.markBroadcastAsSeen("bc_count")
        mockPreferenceStore.getBroadcastTimesShown("bc_count") shouldBeEqualTo 1
        broadcastManager.getEligibleBroadcasts().shouldHaveSize(1)

        broadcastManager.markBroadcastAsSeen("bc_count")
        mockPreferenceStore.getBroadcastTimesShown("bc_count") shouldBeEqualTo 2
        broadcastManager.getEligibleBroadcasts().shouldHaveSize(1)

        broadcastManager.markBroadcastAsSeen("bc_count")
        mockPreferenceStore.getBroadcastTimesShown("bc_count") shouldBeEqualTo 3
        broadcastManager.getEligibleBroadcasts().shouldBeEmpty()
    }

    @Test
    fun markBroadcastAsSeen_givenSingleShowBroadcast_expectPermanentDismissal() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_single_show",
            count = 1,
            delay = 0
        )
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        broadcastManager.markBroadcastAsSeen("bc_single_show")

        mockPreferenceStore.isBroadcastDismissed("bc_single_show") shouldBe true
        broadcastManager.getEligibleBroadcasts().shouldBeEmpty()
    }

    @Test
    fun markBroadcastAsSeen_givenBroadcastWithDelay_expectTemporaryRestriction() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_with_delay",
            count = 3,
            delay = 2 // 2 seconds delay
        )
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        broadcastManager.markBroadcastAsSeen("bc_with_delay")

        mockPreferenceStore.getBroadcastTimesShown("bc_with_delay") shouldBeEqualTo 1
        mockPreferenceStore.isBroadcastInDelayPeriod("bc_with_delay") shouldBe true
        broadcastManager.getEligibleBroadcasts().shouldBeEmpty()
    }

    @Test
    fun getEligibleBroadcasts_givenBroadcastInDelayPeriod_expectNotEligible() {
        val givenBroadcast = createBroadcastMessage(
            queueId = "bc_delay_check",
            count = 5,
            delay = 1
        )
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        // Manually set delay period
        val futureTime = System.currentTimeMillis() + 3000 // 3 seconds from now
        mockPreferenceStore.setBroadcastNextShowTime("bc_delay_check", futureTime)

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()
        eligibleBroadcasts.shouldBeEmpty()
    }

    private fun createBroadcastMessage(
        queueId: String,
        count: Int = 1,
        delay: Int = 0,
        ignoreDismiss: Boolean = false
    ): Message {
        return Message(
            messageId = "msg_$queueId",
            queueId = queueId,
            priority = 1,
            properties = mapOf(
                "gist" to mapOf(
                    "routeRuleAndroid" to null,
                    "elementId" to null,
                    "campaignId" to null,
                    "position" to "CENTER",
                    "persistent" to false,
                    "overlayColor" to null,
                    "broadcast" to mapOf(
                        "frequency" to mapOf(
                            "count" to count,
                            "delay" to delay,
                            "ignoreDismiss" to ignoreDismiss
                        )
                    )
                )
            )
        )
    }

    private fun createRegularMessage(queueId: String): Message {
        return Message(
            messageId = "msg_$queueId",
            queueId = queueId,
            priority = 1,
            properties = mapOf(
                "gist" to mapOf(
                    "routeRuleAndroid" to null,
                    "elementId" to null,
                    "campaignId" to null,
                    "position" to "CENTER",
                    "persistent" to false,
                    "overlayColor" to null
                    // No broadcast property = regular message
                )
            )
        )
    }

    // EDGE CASE TESTS

    @Test
    fun edgeCase_invalidFrequencyValues_expectFiltered() {
        val givenInvalidBroadcast1 = createBroadcastMessage("invalid1", count = -1, delay = 0)
        val givenInvalidBroadcast2 = createBroadcastMessage("invalid2", count = 1, delay = -5)
        val givenValidBroadcast = createBroadcastMessage("valid", count = 2, delay = 1)

        broadcastManager.updateBroadcastsLocalStore(listOf(givenInvalidBroadcast1, givenInvalidBroadcast2, givenValidBroadcast))

        val eligibleBroadcasts = broadcastManager.getEligibleBroadcasts()

        eligibleBroadcasts.size shouldBeEqualTo 1
        eligibleBroadcasts.first().queueId shouldBeEqualTo "valid"
    }

    @Test
    fun edgeCase_multipleFrequencyExhaustion_expectCorrectFiltering() {
        val givenBroadcast1 = createBroadcastMessage("freq1", count = 1, delay = 0) // Single use
        val givenBroadcast2 = createBroadcastMessage("freq2", count = 2, delay = 0) // Two uses
        val givenBroadcast3 = createBroadcastMessage("freq3", count = 0, delay = 0) // Unlimited

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1, givenBroadcast2, givenBroadcast3))

        // All should be eligible initially
        var eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 3

        // Use broadcast1 once - should be permanently dismissed
        broadcastManager.markBroadcastAsSeen("freq1")
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 2
        eligible.none { it.queueId == "freq1" } shouldBe true

        // Use broadcast2 once - should still be eligible
        broadcastManager.markBroadcastAsSeen("freq2")
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 2
        eligible.any { it.queueId == "freq2" } shouldBe true

        // Use broadcast2 second time - should be exhausted
        broadcastManager.markBroadcastAsSeen("freq2")
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "freq3"

        // Use broadcast3 multiple times - should always remain eligible
        repeat(10) {
            broadcastManager.markBroadcastAsSeen("freq3")
            eligible = broadcastManager.getEligibleBroadcasts()
            eligible.size shouldBeEqualTo 1
            eligible.first().queueId shouldBeEqualTo "freq3"
        }
    }

    @Test
    fun edgeCase_delayWithFrequencyExhaustion_expectCorrectTimingAndLimits() {
        val givenBroadcast = createBroadcastMessage("delay_freq", count = 2, delay = 1) // 2 uses, 1s delay

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        // First use
        broadcastManager.markBroadcastAsSeen("delay_freq")
        mockPreferenceStore.getBroadcastTimesShown("delay_freq") shouldBeEqualTo 1
        mockPreferenceStore.isBroadcastInDelayPeriod("delay_freq") shouldBe true
        broadcastManager.getEligibleBroadcasts().shouldBeEmpty()

        // Simulate delay period passing
        val pastTime = System.currentTimeMillis() - 2000 // 2 seconds ago
        mockPreferenceStore.setBroadcastNextShowTime("delay_freq", pastTime)

        // Should be eligible again (1/2 uses consumed)
        var eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1

        // Second use - should exhaust frequency
        broadcastManager.markBroadcastAsSeen("delay_freq")
        mockPreferenceStore.getBroadcastTimesShown("delay_freq") shouldBeEqualTo 2

        // Even after delay passes, should not be eligible (frequency exhausted)
        mockPreferenceStore.setBroadcastNextShowTime("delay_freq", pastTime)
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.shouldBeEmpty()
    }

    @Test
    fun edgeCase_ignoreDismissTrueWithFrequencyLimits_expectDismissIgnoredButFrequencyRespected() {
        val givenBroadcast = createBroadcastMessage("ignore_dismiss_freq", count = 2, delay = 0, ignoreDismiss = true)

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        // Manually dismiss - should be ignored
        broadcastManager.markBroadcastAsDismissed("ignore_dismiss_freq")
        mockPreferenceStore.isBroadcastDismissed("ignore_dismiss_freq") shouldBe false

        // Should still be eligible
        var eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1

        // Use frequency twice
        broadcastManager.markBroadcastAsSeen("ignore_dismiss_freq")
        broadcastManager.markBroadcastAsSeen("ignore_dismiss_freq")
        mockPreferenceStore.getBroadcastTimesShown("ignore_dismiss_freq") shouldBeEqualTo 2

        // Should be exhausted despite ignoreDismiss = true
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.shouldBeEmpty()
    }

    @Test
    fun edgeCase_serverRemovesPartialBroadcasts_expectCorrectCleanup() {
        val givenBroadcast1 = createBroadcastMessage("keep", count = 3, delay = 0)
        val givenBroadcast2 = createBroadcastMessage("remove", count = 3, delay = 0)
        val givenBroadcast3 = createBroadcastMessage("also_remove", count = 3, delay = 0)

        // Initial server response with all 3
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1, givenBroadcast2, givenBroadcast3))

        // Use some tracking data
        broadcastManager.markBroadcastAsSeen("keep")
        broadcastManager.markBroadcastAsSeen("remove")
        broadcastManager.markBroadcastAsDismissed("also_remove")

        // Verify tracking data exists
        mockPreferenceStore.getBroadcastTimesShown("keep") shouldBeEqualTo 1
        mockPreferenceStore.getBroadcastTimesShown("remove") shouldBeEqualTo 1
        mockPreferenceStore.isBroadcastDismissed("also_remove") shouldBe true

        // Server now only returns 1 broadcast
        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1))

        // Only "keep" should remain eligible
        val eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "keep"

        // Tracking data for "keep" should be preserved
        mockPreferenceStore.getBroadcastTimesShown("keep") shouldBeEqualTo 1

        // Tracking data for removed broadcasts should be cleaned up
        mockPreferenceStore.getBroadcastTimesShown("remove") shouldBeEqualTo 0
        mockPreferenceStore.isBroadcastDismissed("also_remove") shouldBe false
    }

    @Test
    fun edgeCase_complexDelayScenario_expectCorrectTimingBehavior() {
        val givenBroadcast1 = createBroadcastMessage("fast", count = 0, delay = 1) // 1s delay
        val givenBroadcast2 = createBroadcastMessage("slow", count = 0, delay = 5) // 5s delay

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast1, givenBroadcast2))

        // Both eligible initially
        var eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 2

        // Use both
        broadcastManager.markBroadcastAsSeen("fast")
        broadcastManager.markBroadcastAsSeen("slow")

        // Both should be in delay period
        mockPreferenceStore.isBroadcastInDelayPeriod("fast") shouldBe true
        mockPreferenceStore.isBroadcastInDelayPeriod("slow") shouldBe true
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.shouldBeEmpty()

        // Simulate 2 seconds passing - only fast should be available
        val twoSecondsAgo = System.currentTimeMillis() - 2000
        mockPreferenceStore.setBroadcastNextShowTime("fast", twoSecondsAgo)

        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "fast"

        // Simulate 6 seconds total passing - both should be available
        val sixSecondsAgo = System.currentTimeMillis() - 6000
        mockPreferenceStore.setBroadcastNextShowTime("slow", sixSecondsAgo)

        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 2
    }

    @Test
    fun edgeCase_zeroDelayWithFrequency_expectImmediateEligibility() {
        val givenBroadcast = createBroadcastMessage("no_delay", count = 3, delay = 0)

        broadcastManager.updateBroadcastsLocalStore(listOf(givenBroadcast))

        // Use and verify immediate eligibility
        repeat(2) {
            var eligible = broadcastManager.getEligibleBroadcasts()
            eligible.size shouldBeEqualTo 1
            broadcastManager.markBroadcastAsSeen("no_delay")
            // Should be immediately eligible again (no delay)
            eligible = broadcastManager.getEligibleBroadcasts()
            eligible.size shouldBeEqualTo 1
        }

        // Third use should exhaust frequency
        var eligible = broadcastManager.getEligibleBroadcasts()
        eligible.size shouldBeEqualTo 1
        broadcastManager.markBroadcastAsSeen("no_delay")
        eligible = broadcastManager.getEligibleBroadcasts()
        eligible.shouldBeEmpty()
    }
}
