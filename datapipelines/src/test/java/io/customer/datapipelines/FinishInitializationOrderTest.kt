package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.CustomerIO
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.module.CustomerIOModuleConfig
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

/**
 * Verifies the ordering invariants restored by [CustomerIO.finishInitialization]:
 *
 * - Modules' `initialize()` runs BEFORE the initial `UserChangedEvent` is
 *   published, so late-subscribing modules (e.g. MessagingInApp) receive the
 *   event live instead of relying on the SharedFlow replay buffer.
 * - The pre-init buffer is drained AFTER all modules have run their
 *   `initialize()`, so buffered `screen()` events published during the drain
 *   reach subscribers via the live flow rather than via replay (where they
 *   could otherwise evict the initial identity event).
 */
class FinishInitializationOrderTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private val testModule = LateSubscribingTestModule()

    override fun setup(testConfig: TestConfig) {
        // Use a relaxed mock EventBus so subscribe/publish calls are recorded
        // in deterministic order without depending on coroutine scheduling.
        val mockEventBus = mockk<EventBus>(relaxed = true)

        super.setup(
            testConfiguration {
                diGraph {
                    sdk { overrideDependency<EventBus>(mockEventBus) }
                }
                sdkConfig {
                    addCustomerIOModule(testModule)
                    // Fill the pre-init buffer past the EventBus SharedFlow
                    // replay cap (=100). Without the deferred-drain fix, the
                    // initial UserChangedEvent would be evicted from replay
                    // before the late-subscribing test module attaches.
                    repeat(101) { index ->
                        CustomerIO.instance().screen("preinit_screen_$index")
                    }
                }
            }
        )
        eventBus = SDKComponent.eventBus
    }

    @Test
    fun givenScreenHeavyPreInitBuffer_expectModuleSubscribesBeforeInitialUserChangedEvent() {
        // The fix moves postUserIdentificationEvents() and the buffer drain
        // into finishInitialization(), which runs AFTER modules.forEach{
        // module.initialize() }. As a result, every module — including the
        // test module here — has already subscribed by the time the initial
        // UserChangedEvent is published.
        verifyOrder {
            eventBus.subscribe(Event.UserChangedEvent::class, any())
            eventBus.publish(any<Event.UserChangedEvent>())
        }
    }

    @Test
    fun givenScreenHeavyPreInitBuffer_expectBufferedScreensPublishedAfterModuleSubscription() {
        // Buffered screen events drain inside finishInitialization() too,
        // so each replayed screen call publishes its ScreenViewedEvent after
        // late-subscribing modules are attached.
        verifyOrder {
            eventBus.subscribe(Event.UserChangedEvent::class, any())
            eventBus.publish(match<Event> { it is Event.ScreenViewedEvent })
        }
        // The buffer's capacity is 100; one of the 101 pre-init screens was
        // dropped, so 100 ScreenViewedEvents should reach the bus.
        verify(exactly = 100) { eventBus.publish(match<Event> { it is Event.ScreenViewedEvent }) }
    }
}

private class LateSubscribingTestModule : CustomerIOModule<LateSubscribingTestModuleConfig> {
    override val moduleName: String = "FinishInitOrderTestModule"
    override val moduleConfig: LateSubscribingTestModuleConfig = LateSubscribingTestModuleConfig

    override fun initialize() {
        // The exact action body is irrelevant — the test asserts the order
        // in which subscribe is called, not what the handler does.
        SDKComponent.eventBus.subscribe(Event.UserChangedEvent::class) { }
    }
}

private object LateSubscribingTestModuleConfig : CustomerIOModuleConfig
