package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.communication.subscribe
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.SecureUserStore
import io.customer.sdk.events.Metric
import io.customer.sdk.util.EventNames
import io.mockk.every
import io.mockk.spyk
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * While the identified user's init write is still encrypting on a background thread, metrics keep
 * flowing to analytics, and modules get UserChangedEvent only once the userId is persisted.
 */
class PendingInitWriteNoLossTest : JUnitTest() {
    private val restoredUserId = "restored-user"
    private val releaseInitWrite = CountDownLatch(1)

    // Stands in for the Keystore-backed store: the init write lands on its own thread when released.
    private val pendingWriteStore = object : SecureUserStore {
        @Volatile
        var persistedUserId: String? = null

        override fun saveUserId(userId: String?, onPersisted: () -> Unit) {
            persistedUserId = userId
            onPersisted()
        }

        override fun saveUserIdInBackground(userId: String?, onPersisted: () -> Unit) {
            thread(name = "slow-keystore-write") {
                releaseInitWrite.await()
                persistedUserId = userId
                onPersisted()
            }
        }

        override fun getUserId(): String? = persistedUserId
        override fun clearAll() {
            persistedUserId = null
        }
    }

    private lateinit var eventBus: EventBus

    // Analytics runs plugins on its own threads, so count with an atomic rather than a plain list.
    private val deliveryMetricsTracked = AtomicInteger()
    private val deliveryMetricCounter = object : Plugin {
        override val type: Plugin.Type = Plugin.Type.After
        override lateinit var analytics: Analytics

        override fun execute(event: BaseEvent): BaseEvent {
            if ((event as? TrackEvent)?.event == EventNames.METRIC_DELIVERY) deliveryMetricsTracked.incrementAndGet()
            return event
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                analytics { spyk(this).also { every { it.userId() } returns restoredUserId } }
                diGraph {
                    android { overrideDependency<SecureUserStore>(pendingWriteStore) }
                }
            }
        )
        eventBus = SDKComponent.eventBus
        analytics.add(deliveryMetricCounter)
    }

    override fun teardown() {
        releaseInitWrite.countDown()
        super.teardown()
    }

    // Bus delivery hops threads, so wait for it rather than assume it's synchronous.
    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    @Test
    fun givenInitWritePending_expectEveryMetricTrackedAndUserEventOnlyAfterPersist() {
        val userIdOnDiskWhenNotified = Collections.synchronizedList(mutableListOf<String?>())
        val userEventReceived = CountDownLatch(1)
        eventBus.subscribe<Event.UserChangedEvent> {
            userIdOnDiskWhenNotified += pendingWriteStore.getUserId()
            userEventReceived.countDown()
        }

        // More than the bus's 100-event replay buffer, all while the Keystore write is stuck.
        repeat(150) { index ->
            eventBus.publish(Event.TrackPushMetricEvent(deliveryId = "delivery-$index", event = Metric.Delivered, deviceToken = "token"))
        }

        waitUntil { deliveryMetricsTracked.get() == 150 }.shouldBeTrue()
        userIdOnDiskWhenNotified.shouldBeEmpty()

        releaseInitWrite.countDown()

        userEventReceived.await(5, TimeUnit.SECONDS).shouldBeTrue()
        userIdOnDiskWhenNotified.toList() shouldBeEqualTo listOf(restoredUserId)
        deliveryMetricsTracked.get() shouldBeEqualTo 150
    }
}
