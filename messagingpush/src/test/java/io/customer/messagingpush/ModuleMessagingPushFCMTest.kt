package io.customer.messagingpush

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.overrideDependency
import io.customer.commontest.extensions.random
import io.customer.commontest.extensions.verifyNever
import io.customer.commontest.extensions.verifyOnce
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.testutils.core.JUnitTest
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.di.SDKComponent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ModuleMessagingPushFCMTest : JUnitTest() {
    private lateinit var eventBus: EventBus
    private lateinit var fcmTokenProviderMock: DeviceTokenProvider
    private lateinit var module: ModuleMessagingPushFCM

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<EventBus>(mockk(relaxed = true))
                    }
                    android {
                        overrideDependency(DeviceTokenProvider::class.java, mockk(relaxed = true))
                    }
                }
            }
        )

        eventBus = SDKComponent.eventBus
        fcmTokenProviderMock = SDKComponent.android().fcmTokenProvider
        module = ModuleMessagingPushFCM()
    }

    override fun teardown() {
        eventBus.removeAllSubscriptions()

        super.teardown()
    }

    @Test
    fun initialize_givenNoFCMTokenAvailable_expectDoNotRegisterToken() {
        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(null)
        }

        module.initialize()

        verifyNever { eventBus.publish(any<Event.RegisterDeviceTokenEvent>()) }
    }

    @Test
    fun initialize_givenFCMTokenAvailable_expectRegisterToken() {
        val givenToken = String.random

        every { fcmTokenProviderMock.getCurrentToken(any()) } answers {
            val callback = firstArg<(String?) -> Unit>()
            callback(givenToken)
        }

        module.initialize()

        verifyOnce { eventBus.publish(Event.RegisterDeviceTokenEvent(token = givenToken)) }
    }
}
