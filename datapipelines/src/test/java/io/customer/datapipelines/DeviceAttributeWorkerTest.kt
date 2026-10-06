package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.BaseEvent
import com.segment.analytics.kotlin.core.IdentifyEvent
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.deviceToken
import io.customer.sdk.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.util.EventNames
import io.mockk.every
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Verifies caller completion and event attribution on real workers, without assuming FIFO delivery. */
class DeviceAttributeWorkerTest : JUnitTest() {
    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfiguration {
                sdkConfig {
                    autoAddCustomerIODestination(true)
                    autoTrackDeviceAttributes(true)
                }
                // Exercise the dependency's real worker pool and store, not a test dispatcher.
                analytics { Analytics(configuration) }
            }
        )
    }

    override fun teardown() {
        analytics.analyticsScope.cancel()
        analytics.shutdown()
        super.teardown()
    }

    @Test
    fun testIdentify_whenWorkerIsCollectingAttributes_thenReturnsWithoutWaitingForCollection() {
        val identityProcessed = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val releaseCollection = CountDownLatch(1)
        val updatesProcessed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<TrackEvent>()
        val collectionThreads = CopyOnWriteArrayList<String>()
        val caller = Executors.newSingleThreadExecutor { task -> Thread(task, "sdk-caller") }
        analytics.add(object : Plugin {
            override val type = Plugin.Type.After
            override lateinit var analytics: Analytics

            override fun execute(event: BaseEvent): BaseEvent {
                if (event is IdentifyEvent && event.userId == "alice") identityProcessed.countDown()
                if (event is TrackEvent) {
                    events.add(event)
                    if (event.event == EventNames.DEVICE_UPDATE) updatesProcessed.countDown()
                }
                return event
            }
        })

        val androidComponent = SDKComponent.android()
        var storedToken: String? = null
        every { androidComponent.globalPreferenceStore.saveDeviceToken(any()) } answers { storedToken = firstArg() }
        every { androidComponent.globalPreferenceStore.getDeviceToken() } answers { storedToken }
        every { androidComponent.deviceStore.buildDeviceAttributes() } answers {
            collectionThreads.add(Thread.currentThread().name)
            collectionStarted.countDown()
            check(releaseCollection.await(10, TimeUnit.SECONDS)) { "Attribute collector was not released" }
            mapOf("app_version" to "2.0")
        }

        try {
            caller.submit { sdkInstance.identify("alice") }.get(5, TimeUnit.SECONDS)
            check(identityProcessed.await(5, TimeUnit.SECONDS)) { "Initial identity was not processed" }

            caller.submit { sdkInstance.registerDeviceToken("token-a") }.get(5, TimeUnit.SECONDS)
            sdkInstance.registeredDeviceToken shouldBeEqualTo "token-a"
            check(collectionStarted.await(5, TimeUnit.SECONDS)) { "Attribute collection did not start" }

            // Collection remains blocked while identify updates the caller-visible state.
            caller.submit { sdkInstance.identify("bob") }.get(5, TimeUnit.SECONDS)
            sdkInstance.isUserIdentified shouldBeEqualTo true
            releaseCollection.countDown()
            check(updatesProcessed.await(5, TimeUnit.SECONDS)) { "Device updates were not processed" }

            collectionThreads.forEach { it shouldNotBeEqualTo "sdk-caller" }
            val updates = events.filter { it.event == EventNames.DEVICE_UPDATE }
            updates.size shouldBeEqualTo 1
            assertEquals(
                mapOf("bob" to "token-a"),
                updates.associate { it.userId to it.context.deviceToken }
            )
        } finally {
            releaseCollection.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun testClearIdentify_whenAttributeCollectionIsBlocked_thenSubmitsDeletionAndDiscardsPendingUpdate() {
        val identityProcessed = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val releaseCollection = CountDownLatch(1)
        val deletionProcessed = CountDownLatch(1)
        val collectionFinished = CountDownLatch(1)
        val updateProcessed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<TrackEvent>()
        val caller = Executors.newSingleThreadExecutor { task -> Thread(task, "sdk-caller") }
        analytics.add(object : Plugin {
            override val type = Plugin.Type.After
            override lateinit var analytics: Analytics

            override fun execute(event: BaseEvent): BaseEvent {
                if (event is IdentifyEvent && event.userId == "alice") identityProcessed.countDown()
                if (event is TrackEvent && event.event in listOf(EventNames.DEVICE_UPDATE, EventNames.DEVICE_DELETE)) {
                    events.add(event)
                    if (event.event == EventNames.DEVICE_DELETE) deletionProcessed.countDown()
                    if (event.event == EventNames.DEVICE_UPDATE) updateProcessed.countDown()
                }
                return event
            }
        })
        val androidComponent = SDKComponent.android()
        var storedToken: String? = null
        every { androidComponent.globalPreferenceStore.saveDeviceToken(any()) } answers { storedToken = firstArg() }
        every { androidComponent.globalPreferenceStore.getDeviceToken() } answers { storedToken }
        every { androidComponent.deviceStore.buildDeviceAttributes() } answers {
            collectionStarted.countDown()
            check(releaseCollection.await(10, TimeUnit.SECONDS)) { "Attribute collector was not released" }
            collectionFinished.countDown()
            mapOf("app_version" to "2.0")
        }

        try {
            caller.submit { sdkInstance.identify("alice") }.get(5, TimeUnit.SECONDS)
            check(identityProcessed.await(5, TimeUnit.SECONDS)) { "Initial identity was not processed" }
            caller.submit { sdkInstance.registerDeviceToken("token-a") }.get(5, TimeUnit.SECONDS)
            check(collectionStarted.await(5, TimeUnit.SECONDS)) { "Attribute collection did not start" }

            caller.submit { sdkInstance.clearIdentify() }.get(5, TimeUnit.SECONDS)
            sdkInstance.isUserIdentified shouldBeEqualTo false
            check(deletionProcessed.await(5, TimeUnit.SECONDS)) { "Deletion waited for attribute collection" }

            releaseCollection.countDown()
            check(collectionFinished.await(5, TimeUnit.SECONDS)) { "Attribute collection did not finish" }
            assertFalse(updateProcessed.await(1, TimeUnit.SECONDS), "Obsolete update was submitted after logout")
            assertEquals(listOf(EventNames.DEVICE_DELETE), events.map { it.event })
            events.forEach {
                it.userId shouldBeEqualTo "alice"
                it.context.deviceToken shouldBeEqualTo "token-a"
            }
        } finally {
            releaseCollection.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun testClearInstance_whenCollectionIsBlocked_thenCancelledWorkerDoesNotSubmitUpdate() {
        val identityProcessed = CountDownLatch(1)
        val collectionStarted = CountDownLatch(1)
        val releaseCollection = CountDownLatch(1)
        val collectionFinished = CountDownLatch(1)
        val analyticsStillRunning = CountDownLatch(1)
        val updateProcessed = CountDownLatch(1)
        analytics.add(object : Plugin {
            override val type = Plugin.Type.After
            override lateinit var analytics: Analytics

            override fun execute(event: BaseEvent): BaseEvent {
                if (event is IdentifyEvent && event.userId == "alice") identityProcessed.countDown()
                if (event is TrackEvent && event.event == "analytics-still-running") analyticsStillRunning.countDown()
                if (event is TrackEvent && event.event == EventNames.DEVICE_UPDATE) updateProcessed.countDown()
                return event
            }
        })
        val androidComponent = SDKComponent.android()
        every { androidComponent.deviceStore.buildDeviceAttributes() } answers {
            collectionStarted.countDown()
            check(releaseCollection.await(10, TimeUnit.SECONDS)) { "Attribute collector was not released" }
            collectionFinished.countDown()
            mapOf("app_version" to "2.0")
        }

        try {
            sdkInstance.identify("alice")
            check(identityProcessed.await(5, TimeUnit.SECONDS)) { "Initial identity was not processed" }
            sdkInstance.registerDeviceToken("token-a")
            check(collectionStarted.await(5, TimeUnit.SECONDS)) { "Attribute collection did not start" }

            CustomerIO.clearInstance()
            // Keep analytics alive to distinguish worker cancellation from downstream shutdown.
            sdkInstance.track("analytics-still-running")
            check(analyticsStillRunning.await(5, TimeUnit.SECONDS)) { "Analytics stopped unexpectedly" }
            releaseCollection.countDown()
            check(collectionFinished.await(5, TimeUnit.SECONDS)) { "Attribute collection did not finish" }
            assertFalse(updateProcessed.await(1, TimeUnit.SECONDS), "Cancelled worker submitted a device update")
        } finally {
            releaseCollection.countDown()
        }
    }
}
