package io.customer.messaginginapp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.messaginginapp.gist.presentation.GistModalActivity
import io.customer.messaginginapp.gist.presentation.hostwindow.HostWindowModalPresenter
import io.customer.messaginginapp.gist.utilities.ModalMessageParser
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.ModalPresentation
import io.customer.sdk.core.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.reduxkotlin.Store
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers how [displayModalMessageMiddleware] chooses between the GistModalActivity and the host-window
 * presenter from the configured [ModalPresentation] (MBL-2812). The default must keep the activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModalMessageDisplayRoutingTest : IntegrationTest() {
    private val mockLogger = mockk<Logger>(relaxed = true)
    private val presenter = mockk<HostWindowModalPresenter>(relaxed = true)
    private val store: Store<InAppMessagingState> = mockk(relaxed = true)
    private val nextFn: (Any) -> Any = mockk(relaxed = true)
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    override fun setup(testConfig: TestConfig) {
        every { store.state } returns InAppMessagingState()
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<Logger>(mockLogger)
                        overrideDependency<HostWindowModalPresenter>(presenter)
                    }
                }
            }
        )
    }

    private fun useModalPresentation(modalPresentation: ModalPresentation) {
        ModuleMessagingInApp(
            MessagingInAppModuleConfig.Builder(siteId = "test-site").setModalPresentation(modalPresentation).build()
        ).attachToSDKComponent()
    }

    private val message = MessageBuilderMock.createMessage(queueId = "queue-1", position = MessagePosition.TOP)

    @Test
    fun loadMessage_givenActivityPresentation_expectModalActivityStartedAndPresenterUntouched() {
        useModalPresentation(ModalPresentation.ACTIVITY)
        val action = InAppMessagingAction.LoadMessage(message, MessagePosition.BOTTOM)

        displayModalMessageMiddleware()(store)(nextFn)(action)

        val started = shadowOf(application).nextStartedActivity.shouldNotBeNull()
        started.component?.className shouldBeEqualTo GistModalActivity::class.java.name
        started.getStringExtra(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION) shouldBeEqualTo "BOTTOM"
        verify(exactly = 0) { presenter.present(any(), any()) }
        verify(exactly = 1) { nextFn(action) }
    }

    @Test
    fun loadMessage_givenHostWindowPresentation_expectPresentedInHostWindowAndNoActivityStarted() {
        useModalPresentation(ModalPresentation.HOST_WINDOW)
        val action = InAppMessagingAction.LoadMessage(message, MessagePosition.BOTTOM)

        displayModalMessageMiddleware()(store)(nextFn)(action)

        verify(exactly = 1) { presenter.present(message, MessagePosition.BOTTOM) }
        shadowOf(application).nextStartedActivity.shouldBeNull()
        verify(exactly = 1) { nextFn(action) }
    }

    @Test
    fun loadMessage_givenHostWindowPresentationAndNoExplicitPosition_expectMessagePositionUsed() {
        useModalPresentation(ModalPresentation.HOST_WINDOW)
        val action = InAppMessagingAction.LoadMessage(message)

        displayModalMessageMiddleware()(store)(nextFn)(action)

        verify(exactly = 1) { presenter.present(message, MessagePosition.TOP) }
    }

    @Test
    fun loadMessage_givenModalAlreadyDisplayed_expectErrorReportedAndNothingPresented() {
        useModalPresentation(ModalPresentation.HOST_WINDOW)
        every { store.state } returns InAppMessagingState(modalMessageState = ModalMessageState.Displayed(message))
        val action = InAppMessagingAction.LoadMessage(message)

        displayModalMessageMiddleware()(store)(nextFn)(action)

        verify(exactly = 0) { presenter.present(any(), any()) }
        shadowOf(application).nextStartedActivity.shouldBeNull()
        verify(exactly = 1) { nextFn(match { it is InAppMessagingAction.ReportError }) }
    }
}
