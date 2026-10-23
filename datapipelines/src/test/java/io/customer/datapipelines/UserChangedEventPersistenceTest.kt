package io.customer.datapipelines

import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.SecureUserStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

/**
 * [Event.UserChangedEvent] is published only once the userId is persisted, so modules that read
 * [SecureUserStore] when notified find it. At init the save runs in the background, keeping the
 * Keystore off the caller's thread.
 */
class UserChangedEventPersistenceTest : JUnitTest() {
    private val restoredUserId = "restored-user"
    private val publishedEvents = mutableListOf<Event>()

    // 'mock' prefix to avoid shadowing the real androidSDKComponent.secureUserStore.
    private val mockSecureUserStore: SecureUserStore = mockk(relaxed = true)
    private val initSaveCallback = slot<() -> Unit>()

    override fun setup(testConfig: TestConfig) {
        every { mockSecureUserStore.saveUserIdInBackground(restoredUserId, capture(initSaveCallback)) } just Runs

        super.setup(
            testConfiguration {
                // A user identified in a previous run, restored by analytics before CustomerIO init.
                analytics { spyk(this).also { every { it.userId() } returns restoredUserId } }
                diGraph {
                    sdk {
                        val mockEventBus = mockk<EventBus>(relaxed = true)
                        every { mockEventBus.publish(any()) } answers { publishedEvents += firstArg<Event>() }
                        overrideDependency<EventBus>(mockEventBus)
                    }
                    android { overrideDependency<SecureUserStore>(mockSecureUserStore) }
                }
            }
        )
    }

    private fun userChangedEvents() = publishedEvents.filterIsInstance<Event.UserChangedEvent>()

    @Test
    fun givenIdentifiedUserAtInit_expectSaveInBackgroundAndEventOnlyAfterItLands() {
        verify(exactly = 0) { mockSecureUserStore.saveUserId(any(), any()) }
        userChangedEvents().shouldBeEmpty()

        initSaveCallback.captured.invoke()

        userChangedEvents() shouldBeEqualTo listOf(
            Event.UserChangedEvent(userId = restoredUserId, anonymousId = analytics.anonymousId())
        )
    }

    @Test
    fun givenIdentify_expectEventOnlyAfterSaveReportsPersisted() {
        val identifySaveCallback = slot<() -> Unit>()
        every { mockSecureUserStore.saveUserId("user-b", capture(identifySaveCallback)) } just Runs

        sdkInstance.identify("user-b")

        userChangedEvents().shouldBeEmpty()

        identifySaveCallback.captured.invoke()

        userChangedEvents().map { it.userId } shouldBeEqualTo listOf("user-b")
    }
}
