package io.customer.messaginginapp.ui.bridge

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.testutils.core.JUnitTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class InAppHostViewDelegateTest : JUnitTest() {
    private lateinit var hostViewMock: ViewGroup
    private lateinit var delegate: InAppHostViewDelegate

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(mockk<InAppMessagingManager>(relaxed = true))
                    }
                }
            }
        )

        hostViewMock = mockk(relaxed = true)
        delegate = InAppHostViewDelegateImpl(hostViewMock)
    }

    @Test
    fun addChildView_givenEngineWebView_expectViewAddedToParent() {
        every { hostViewMock.addView(any()) } just Runs
        val engineWebView = mockk<EngineWebView>()
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>().apply {
            every { getView() } returns engineWebView
        }

        delegate.addView(engineWebViewDelegate)

        verify { hostViewMock.addView(engineWebView) }
    }

    @Test
    fun removeChildView_givenEngineWebView_expectViewRemovedFromParent() {
        every { hostViewMock.removeView(any()) } just Runs
        val engineWebView = mockk<EngineWebView>()
        val engineWebViewDelegate = mockk<EngineWebViewDelegate>().apply {
            every { getView() } returns engineWebView
        }

        delegate.removeView(engineWebViewDelegate)

        verify { hostViewMock.removeView(engineWebView) }
    }

    @Test
    fun createEngineWebViewInstance_givenContext_expectNewInstanceCreated() {
        val hostView = spyk(FrameLayout(contextMock)).apply {
            every { context } returns contextMock
        }
        val delegate = InAppHostViewDelegateImpl(hostView)

        val result = delegate.createEngineWebViewInstance()

        result.shouldBeInstanceOf<EngineWebView>()
    }

    @Test
    fun postOnUIThread_givenRunnable_expectRunnablePostedToView() {
        val runnable = mockk<() -> Unit>(relaxed = true)
        every { hostViewMock.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }

        delegate.post(runnable)

        verify { runnable.invoke() }
    }

    @Test
    fun setVisibility_givenVisibleTrue_expectViewVisible() {
        every { hostViewMock.isVisible = true } just Runs

        delegate.isVisible = true

        verify { hostViewMock.visibility = View.VISIBLE }
    }

    @Test
    fun setVisibility_givenVisibleFalse_expectViewGone() {
        every { hostViewMock.isVisible = false } just Runs

        delegate.isVisible = false

        verify { hostViewMock.visibility = View.GONE }
    }
}
