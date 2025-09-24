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
}
