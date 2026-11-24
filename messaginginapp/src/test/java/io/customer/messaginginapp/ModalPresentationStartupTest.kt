package io.customer.messaginginapp

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.gist.presentation.hostwindow.HostActivityTracker
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.ModalPresentation
import io.customer.sdk.core.util.Logger
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host activity tracker is only wired in when the host chose [ModalPresentation.HOST_WINDOW], so the
 * default presentation adds no activity lifecycle callbacks (MBL-2812).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModalPresentationStartupTest : IntegrationTest() {
    private val tracker = mockk<HostActivityTracker>(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockk(relaxed = true))
                        overrideDependency<HostActivityTracker>(tracker)
                    }
                }
            }
        )
    }

    private fun module(modalPresentation: ModalPresentation) = ModuleMessagingInApp(
        MessagingInAppModuleConfig.Builder(siteId = "test-site").setModalPresentation(modalPresentation).build()
    ).also { it.attachToSDKComponent() }

    @Test
    fun initialize_givenActivityPresentation_expectHostActivityTrackerUntouched() {
        module(ModalPresentation.ACTIVITY).initialize()

        verify(exactly = 0) { tracker.listener = any() }
    }

    @Test
    fun initialize_givenHostWindowPresentation_expectPresenterListeningToTracker() {
        module(ModalPresentation.HOST_WINDOW).initialize()

        verify(exactly = 1) { tracker.listener = any() }
    }
}
