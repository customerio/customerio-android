package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.AnonymousMessageManagerImpl
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.state.InAppMessagingManager
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

class AnonymousMessageManagerTest : JUnitTest() {

    private lateinit var anonymousManager: AnonymousMessageManagerImpl
    private lateinit var mockPreferenceStore: InAppPreferenceStore
    private lateinit var mockInAppMessagingManager: InAppMessagingManager
    private lateinit var state: InAppMessagingState

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(mockk<InAppPreferenceStore>(relaxed = true))
                        overrideDependency(mockk<InAppMessagingManager>(relaxed = true))
                    }
                }
            }
        )

        mockPreferenceStore = SDKComponent.inAppPreferenceStore
        mockInAppMessagingManager = SDKComponent.inAppMessagingManager

        // Configure mock behavior to act like a real preference store
        val anonymousTimesShownMap = mutableMapOf<String, Int>()
        val anonymousDismissedMap = mutableMapOf<String, Boolean>()
        val anonymousNextShowTimeMap = mutableMapOf<String, Long>()
        var anonymousMessagesData: String? = null
        var anonymousMessagesExpiry: Long = 0

        every { mockPreferenceStore.getAnonymousTimesShown(any()) } answers { anonymousTimesShownMap[firstArg()] ?: 0 }
        every { mockPreferenceStore.incrementAnonymousTimesShown(any()) } answers {
            val messageId = firstArg<String>()
            anonymousTimesShownMap[messageId] = (anonymousTimesShownMap[messageId] ?: 0) + 1
        }
        every { mockPreferenceStore.isAnonymousDismissed(any()) } answers { anonymousDismissedMap[firstArg()] ?: false }
        every { mockPreferenceStore.setAnonymousDismissed(any(), any()) } answers {
            val messageId = firstArg<String>()
            val dismissed = secondArg<Boolean>()
            if (dismissed) anonymousDismissedMap[messageId] = true else anonymousDismissedMap.remove(messageId)
        }
        every { mockPreferenceStore.saveAnonymousMessages(any(), any()) } answers {
            anonymousMessagesData = firstArg()
            anonymousMessagesExpiry = secondArg()
        }
        every { mockPreferenceStore.getAnonymousMessages() } answers {
            if (System.currentTimeMillis() > anonymousMessagesExpiry) {
                anonymousMessagesData = null
                anonymousMessagesExpiry = 0
            }
            anonymousMessagesData
        }
        every { mockPreferenceStore.clearAnonymousTracking(any()) } answers {
            val messageId = firstArg<String>()
            anonymousTimesShownMap.remove(messageId)
            anonymousDismissedMap.remove(messageId)
            anonymousNextShowTimeMap.remove(messageId)
        }
        every { mockPreferenceStore.clearAllAnonymousData() } answers {
            anonymousMessagesData = null
            anonymousMessagesExpiry = 0
        }
        every { mockPreferenceStore.setAnonymousNextShowTime(any(), any()) } answers {
            val messageId = firstArg<String>()
            val nextShowTime = secondArg<Long>()
            anonymousNextShowTimeMap[messageId] = nextShowTime
        }
        every { mockPreferenceStore.getAnonymousNextShowTime(any()) } answers { anonymousNextShowTimeMap[firstArg()] ?: 0 }
        every { mockPreferenceStore.isAnonymousInDelayPeriod(any()) } answers {
            val messageId = firstArg<String>()
            val nextShowTime = anonymousNextShowTimeMap[messageId] ?: 0
            nextShowTime > 0 && System.currentTimeMillis() < nextShowTime
        }

        state = InAppMessagingState(userId = "testuser123")
        every { mockInAppMessagingManager.getCurrentState() } returns state
        anonymousManager = AnonymousMessageManagerImpl()
    }

    @Test
    fun updateAnonymousMessagesLocalStore_givenSingleUseMessage_expectStoredAndEligibleOnce() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc1",
            count = 1,
            delay = 0,
            ignoreDismiss = false
        )
        val givenRegularMessage = createRegularMessage("reg1")

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast, givenRegularMessage))

        val eligibleFirst = anonymousManager.getEligibleAnonymousMessages()
        eligibleFirst.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
        eligibleFirst.any { it.queueId == givenRegularMessage.queueId } shouldBe false

        anonymousManager.markAnonymousAsSeen("bc1")

        val eligibleAfterSeen = anonymousManager.getEligibleAnonymousMessages()
        eligibleAfterSeen.shouldBeEmpty()
    }

    @Test
    fun getEligibleAnonymousMessages_givenUnlimitedMessage_expectAlwaysEligible() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_unlimited",
            count = 0, // Unlimited
            delay = 0,
            ignoreDismiss = false
        )

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        repeat(5) {
            val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
            eligibleBroadcasts.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
            anonymousManager.markAnonymousAsSeen("bc_unlimited")
        }

        val finalEligible = anonymousManager.getEligibleAnonymousMessages()
        finalEligible.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
    }

    @Test
    fun markAnonymousAsDismissed_givenIgnoreDismissTrue_expectStillEligible() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_ignore_dismiss",
            count = 0,
            delay = 0,
            ignoreDismiss = true
        )

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))
        anonymousManager.markAnonymousAsDismissed("bc_ignore_dismiss")

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
        eligibleBroadcasts.any { it.queueId == givenBroadcast.queueId && it.messageId == givenBroadcast.messageId } shouldBe true
        mockPreferenceStore.isAnonymousDismissed("bc_ignore_dismiss") shouldBe false
    }

    @Test
    fun markAnonymousAsDismissed_givenIgnoreDismissFalse_expectNotEligible() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_respect_dismiss",
            count = 0,
            delay = 0,
            ignoreDismiss = false
        )

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))
        anonymousManager.markAnonymousAsDismissed("bc_respect_dismiss")

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
        eligibleBroadcasts shouldNotContain givenBroadcast
        mockPreferenceStore.isAnonymousDismissed("bc_respect_dismiss") shouldBe true
    }

    @Test
    fun updateAnonymousMessagesLocalStore_givenMessageRemovedFromServer_expectCleanup() {
        val givenBroadcast1 = createAnonymousMessage("bc1", count = 3)
        val givenBroadcast2 = createAnonymousMessage("bc2", count = 3)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1, givenBroadcast2))
        anonymousManager.markAnonymousAsSeen("bc1")
        anonymousManager.markAnonymousAsSeen("bc2")
        mockPreferenceStore.getAnonymousTimesShown("bc1") shouldBeEqualTo 1
        mockPreferenceStore.getAnonymousTimesShown("bc2") shouldBeEqualTo 1

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1))

        mockPreferenceStore.getAnonymousTimesShown("bc1") shouldBeEqualTo 1
        mockPreferenceStore.getAnonymousTimesShown("bc2") shouldBeEqualTo 0
        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
        eligibleBroadcasts.map { it.queueId } shouldBeEqualTo listOf("bc1")
    }

    @Test
    fun updateAnonymousMessagesLocalStore_givenNoMessageFromServer_expectCompleteCleanup() {
        val givenBroadcast = createAnonymousMessage("bc_expire", count = 5)
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))
        anonymousManager.markAnonymousAsSeen("bc_expire")
        anonymousManager.getEligibleAnonymousMessages().shouldHaveSize(1)
        mockPreferenceStore.getAnonymousTimesShown("bc_expire") shouldBeEqualTo 1

        anonymousManager.updateAnonymousMessagesLocalStore(emptyList())

        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()
        mockPreferenceStore.getAnonymousMessages() shouldBe null
    }

    @Test
    fun getEligibleAnonymousMessages_calledMultipleTimes_expectCacheOptimization() {
        val givenBroadcast = createAnonymousMessage("bc_cache", count = 3)
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        val result1 = anonymousManager.getEligibleAnonymousMessages()
        val result2 = anonymousManager.getEligibleAnonymousMessages()
        val result3 = anonymousManager.getEligibleAnonymousMessages()

        result1 shouldBeEqualTo result2
        result2 shouldBeEqualTo result3
        result1.shouldHaveSize(1)
    }

    @Test
    fun getEligibleAnonymousMessages_givenMessageWithoutQueueId_expectFilteredOut() {
        val givenInvalidBroadcast = createAnonymousMessage("bc_valid", count = 1).copy(queueId = null)
        val givenValidBroadcast = createAnonymousMessage("bc_invalid", count = 1)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenInvalidBroadcast, givenValidBroadcast))

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()

        eligibleBroadcasts.size shouldBeEqualTo 1
        eligibleBroadcasts.first().queueId shouldBeEqualTo "bc_invalid"
    }

    @Test
    fun updateAnonymousMessagesLocalStore_givenMixedMessages_expectOnlyBroadcastsProcessed() {
        val givenBroadcast1 = createAnonymousMessage("bc1", count = 2)
        val givenRegular1 = createRegularMessage("reg1")
        val givenBroadcast2 = createAnonymousMessage("bc2", count = 1)
        val givenRegular2 = createRegularMessage("reg2")

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1, givenRegular1, givenBroadcast2, givenRegular2))

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
        eligibleBroadcasts.size shouldBeEqualTo 2
        eligibleBroadcasts.map { it.queueId } shouldBeEqualTo listOf("bc1", "bc2")
    }

    @Test
    fun updateAnonymousMessagesLocalStore_givenNoUserToken_expectNoOperations() {
        val givenStateNoUser = InAppMessagingState(userId = null, anonymousId = null)
        every { mockInAppMessagingManager.getCurrentState() } returns givenStateNoUser
        val givenBroadcast = createAnonymousMessage("bc_no_user", count = 1)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()
        mockPreferenceStore.getAnonymousMessages() shouldBe null
    }

    @Test
    fun markAnonymousAsSeen_givenMultipleCalls_expectAccurateCountTracking() {
        val givenBroadcast = createAnonymousMessage("bc_count", count = 3)
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))
        mockPreferenceStore.getAnonymousTimesShown("bc_count") shouldBeEqualTo 0

        anonymousManager.markAnonymousAsSeen("bc_count")
        mockPreferenceStore.getAnonymousTimesShown("bc_count") shouldBeEqualTo 1
        anonymousManager.getEligibleAnonymousMessages().shouldHaveSize(1)

        anonymousManager.markAnonymousAsSeen("bc_count")
        mockPreferenceStore.getAnonymousTimesShown("bc_count") shouldBeEqualTo 2
        anonymousManager.getEligibleAnonymousMessages().shouldHaveSize(1)

        anonymousManager.markAnonymousAsSeen("bc_count")
        mockPreferenceStore.getAnonymousTimesShown("bc_count") shouldBeEqualTo 3
        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()
    }

    @Test
    fun markAnonymousAsSeen_givenSingleShowBroadcast_expectPermanentDismissal() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_single_show",
            count = 1,
            delay = 0
        )
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        anonymousManager.markAnonymousAsSeen("bc_single_show")

        mockPreferenceStore.isAnonymousDismissed("bc_single_show") shouldBe true
        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()
    }

    @Test
    fun markAnonymousAsSeen_givenBroadcastWithDelay_expectTemporaryRestriction() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_with_delay",
            count = 3,
            delay = 2 // 2 seconds delay
        )
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        anonymousManager.markAnonymousAsSeen("bc_with_delay")

        mockPreferenceStore.getAnonymousTimesShown("bc_with_delay") shouldBeEqualTo 1
        mockPreferenceStore.isAnonymousInDelayPeriod("bc_with_delay") shouldBe true
        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()
    }

    @Test
    fun getEligibleAnonymousMessages_givenBroadcastInDelayPeriod_expectNotEligible() {
        val givenBroadcast = createAnonymousMessage(
            queueId = "bc_delay_check",
            count = 5,
            delay = 1
        )
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        // Manually set delay period
        val futureTime = System.currentTimeMillis() + 3000 // 3 seconds from now
        mockPreferenceStore.setAnonymousNextShowTime("bc_delay_check", futureTime)

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()
        eligibleBroadcasts.shouldBeEmpty()
    }

    private fun createAnonymousMessage(
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
        val givenInvalidBroadcast1 = createAnonymousMessage("invalid1", count = -1, delay = 0)
        val givenInvalidBroadcast2 = createAnonymousMessage("invalid2", count = 1, delay = -5)
        val givenValidBroadcast = createAnonymousMessage("valid", count = 2, delay = 1)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenInvalidBroadcast1, givenInvalidBroadcast2, givenValidBroadcast))

        val eligibleBroadcasts = anonymousManager.getEligibleAnonymousMessages()

        eligibleBroadcasts.size shouldBeEqualTo 1
        eligibleBroadcasts.first().queueId shouldBeEqualTo "valid"
    }

    @Test
    fun edgeCase_multipleFrequencyExhaustion_expectCorrectFiltering() {
        val givenBroadcast1 = createAnonymousMessage("freq1", count = 1, delay = 0) // Single use
        val givenBroadcast2 = createAnonymousMessage("freq2", count = 2, delay = 0) // Two uses
        val givenBroadcast3 = createAnonymousMessage("freq3", count = 0, delay = 0) // Unlimited

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1, givenBroadcast2, givenBroadcast3))

        // All should be eligible initially
        var eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 3

        // Use broadcast1 once - should be permanently dismissed
        anonymousManager.markAnonymousAsSeen("freq1")
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 2
        eligible.none { it.queueId == "freq1" } shouldBe true

        // Use broadcast2 once - should still be eligible
        anonymousManager.markAnonymousAsSeen("freq2")
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 2
        eligible.any { it.queueId == "freq2" } shouldBe true

        // Use broadcast2 second time - should be exhausted
        anonymousManager.markAnonymousAsSeen("freq2")
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "freq3"

        // Use broadcast3 multiple times - should always remain eligible
        repeat(10) {
            anonymousManager.markAnonymousAsSeen("freq3")
            eligible = anonymousManager.getEligibleAnonymousMessages()
            eligible.size shouldBeEqualTo 1
            eligible.first().queueId shouldBeEqualTo "freq3"
        }
    }

    @Test
    fun edgeCase_delayWithFrequencyExhaustion_expectCorrectTimingAndLimits() {
        val givenBroadcast = createAnonymousMessage("delay_freq", count = 2, delay = 1) // 2 uses, 1s delay

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        // First use
        anonymousManager.markAnonymousAsSeen("delay_freq")
        mockPreferenceStore.getAnonymousTimesShown("delay_freq") shouldBeEqualTo 1
        mockPreferenceStore.isAnonymousInDelayPeriod("delay_freq") shouldBe true
        anonymousManager.getEligibleAnonymousMessages().shouldBeEmpty()

        // Simulate delay period passing
        val pastTime = System.currentTimeMillis() - 2000 // 2 seconds ago
        mockPreferenceStore.setAnonymousNextShowTime("delay_freq", pastTime)

        // Should be eligible again (1/2 uses consumed)
        var eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1

        // Second use - should exhaust frequency
        anonymousManager.markAnonymousAsSeen("delay_freq")
        mockPreferenceStore.getAnonymousTimesShown("delay_freq") shouldBeEqualTo 2

        // Even after delay passes, should not be eligible (frequency exhausted)
        mockPreferenceStore.setAnonymousNextShowTime("delay_freq", pastTime)
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.shouldBeEmpty()
    }

    @Test
    fun edgeCase_ignoreDismissTrueWithFrequencyLimits_expectDismissIgnoredButFrequencyRespected() {
        val givenBroadcast = createAnonymousMessage("ignore_dismiss_freq", count = 2, delay = 0, ignoreDismiss = true)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        // Manually dismiss - should be ignored
        anonymousManager.markAnonymousAsDismissed("ignore_dismiss_freq")
        mockPreferenceStore.isAnonymousDismissed("ignore_dismiss_freq") shouldBe false

        // Should still be eligible
        var eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1

        // Use frequency twice
        anonymousManager.markAnonymousAsSeen("ignore_dismiss_freq")
        anonymousManager.markAnonymousAsSeen("ignore_dismiss_freq")
        mockPreferenceStore.getAnonymousTimesShown("ignore_dismiss_freq") shouldBeEqualTo 2

        // Should be exhausted despite ignoreDismiss = true
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.shouldBeEmpty()
    }

    @Test
    fun edgeCase_serverRemovesPartialBroadcasts_expectCorrectCleanup() {
        val givenBroadcast1 = createAnonymousMessage("keep", count = 3, delay = 0)
        val givenBroadcast2 = createAnonymousMessage("remove", count = 3, delay = 0)
        val givenBroadcast3 = createAnonymousMessage("also_remove", count = 3, delay = 0)

        // Initial server response with all 3
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1, givenBroadcast2, givenBroadcast3))

        // Use some tracking data
        anonymousManager.markAnonymousAsSeen("keep")
        anonymousManager.markAnonymousAsSeen("remove")
        anonymousManager.markAnonymousAsDismissed("also_remove")

        // Verify tracking data exists
        mockPreferenceStore.getAnonymousTimesShown("keep") shouldBeEqualTo 1
        mockPreferenceStore.getAnonymousTimesShown("remove") shouldBeEqualTo 1
        mockPreferenceStore.isAnonymousDismissed("also_remove") shouldBe true

        // Server now only returns 1 broadcast
        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1))

        // Only "keep" should remain eligible
        val eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "keep"

        // Tracking data for "keep" should be preserved
        mockPreferenceStore.getAnonymousTimesShown("keep") shouldBeEqualTo 1

        // Tracking data for removed broadcasts should be cleaned up
        mockPreferenceStore.getAnonymousTimesShown("remove") shouldBeEqualTo 0
        mockPreferenceStore.isAnonymousDismissed("also_remove") shouldBe false
    }

    @Test
    fun edgeCase_complexDelayScenario_expectCorrectTimingBehavior() {
        val givenBroadcast1 = createAnonymousMessage("fast", count = 0, delay = 1) // 1s delay
        val givenBroadcast2 = createAnonymousMessage("slow", count = 0, delay = 5) // 5s delay

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast1, givenBroadcast2))

        // Both eligible initially
        var eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 2

        // Use both
        anonymousManager.markAnonymousAsSeen("fast")
        anonymousManager.markAnonymousAsSeen("slow")

        // Both should be in delay period
        mockPreferenceStore.isAnonymousInDelayPeriod("fast") shouldBe true
        mockPreferenceStore.isAnonymousInDelayPeriod("slow") shouldBe true
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.shouldBeEmpty()

        // Simulate 2 seconds passing - only fast should be available
        val twoSecondsAgo = System.currentTimeMillis() - 2000
        mockPreferenceStore.setAnonymousNextShowTime("fast", twoSecondsAgo)

        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1
        eligible.first().queueId shouldBeEqualTo "fast"

        // Simulate 6 seconds total passing - both should be available
        val sixSecondsAgo = System.currentTimeMillis() - 6000
        mockPreferenceStore.setAnonymousNextShowTime("slow", sixSecondsAgo)

        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 2
    }

    @Test
    fun edgeCase_zeroDelayWithFrequency_expectImmediateEligibility() {
        val givenBroadcast = createAnonymousMessage("no_delay", count = 3, delay = 0)

        anonymousManager.updateAnonymousMessagesLocalStore(listOf(givenBroadcast))

        // Use and verify immediate eligibility
        repeat(2) {
            var eligible = anonymousManager.getEligibleAnonymousMessages()
            eligible.size shouldBeEqualTo 1
            anonymousManager.markAnonymousAsSeen("no_delay")
            // Should be immediately eligible again (no delay)
            eligible = anonymousManager.getEligibleAnonymousMessages()
            eligible.size shouldBeEqualTo 1
        }

        // Third use should exhaust frequency
        var eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.size shouldBeEqualTo 1
        anonymousManager.markAnonymousAsSeen("no_delay")
        eligible = anonymousManager.getEligibleAnonymousMessages()
        eligible.shouldBeEmpty()
    }
}
