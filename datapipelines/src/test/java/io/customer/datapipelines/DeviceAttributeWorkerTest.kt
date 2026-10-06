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
import org.junit.jupiter.api.Test

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
        val updatesProcessed = CountDownLatch(2)
        val events = CopyOnWriteArrayList<TrackEvent>()
        val collectionThreads = CopyOnWriteArrayList<String>()
        val caller = Executors.newSingleThreadExecutor { task -> Thread(task, "sdk-caller") }
        analytics.add(object : Plugin {
            override val type = Plugin.Type.After
            override lateinit var analytics: Analytics

            override fun execute(event: BaseEvent): BaseEvent {
                if (event is IdentifyEvent && event.userId == "alice") identityProcessed.countDown()
                if (event is TrackEvent && event.event == EventNames.DEVICE_UPDATE) {
                    events.add(event)
                    updatesProcessed.countDown()
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
            events.map { it.userId }.sorted() shouldBeEqualTo listOf("alice", "bob")
            events.map { it.context.deviceToken } shouldBeEqualTo listOf("token-a", "token-a")
        } finally {
            releaseCollection.countDown()
            caller.shutdownNow()
        }
    }
}
