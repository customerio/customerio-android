package io.customer.messaginginapp.gist.presentation.engine

import android.app.Activity
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Guards the JavaScript bridge across a host lifecycle owner being replaced.
 *
 * [EngineWebView] subscribes to the view-tree lifecycle owner it finds in [EngineWebView.setup].
 * When that owner is a Fragment's view lifecycle and the host keeps the same [EngineWebView]
 * instance alive while the Fragment's view is destroyed and recreated (React Native does this
 * on every native-stack push/pop), the old owner pauses the engine — detaching the JavaScript
 * interface — and a new owner takes its place. Unless the engine re-subscribes when it is
 * re-attached to the window, `ON_RESUME` never arrives and every renderer event is dropped.
 *
 * The interface presence is observed through Robolectric's WebView shadow, which records
 * `addJavascriptInterface` / `removeJavascriptInterface` by name.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewLifecycleTest : IntegrationTest() {

    private val inAppMessagingManager: InAppMessagingManager = mockk(relaxed = true)

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency(inAppMessagingManager)
                    }
                }
            }
        )
        // Default state -> GistEnvironment.PROD, so setup() can resolve a real renderer URL.
        every { inAppMessagingManager.getCurrentState() } returns InAppMessagingState()
    }

    @Test
    fun onAttachedToWindow_givenNewLifecycleOwnerAfterPreviousOneDestroyed_expectInterfaceReattached() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val firstOwner = FakeLifecycleOwner()
        val firstHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(firstOwner) }
        val engineWebView = EngineWebView(activity)
        firstHost.addView(engineWebView)
        activity.setContentView(firstHost)
        firstOwner.registry.currentState = Lifecycle.State.RESUMED

        engineWebView.setup(givenConfiguration())
        val webView = shadowOf(engineWebView.getChildAt(0) as WebView)
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldNotBeNull()

        // Mirror FragmentStateManager tearing the host Fragment's view down: pause, remove the
        // view from its container, destroy the view lifecycle.
        firstOwner.registry.currentState = Lifecycle.State.STARTED
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()
        firstHost.removeView(engineWebView)
        firstOwner.registry.currentState = Lifecycle.State.DESTROYED

        // The Fragment comes back with a brand-new view lifecycle owner and the same engine view.
        val secondOwner = FakeLifecycleOwner()
        secondOwner.registry.currentState = Lifecycle.State.RESUMED
        val secondHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(secondOwner) }
        secondHost.addView(engineWebView)
        activity.setContentView(secondHost)

        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldNotBeNull()

        // And the new owner now drives the engine.
        secondOwner.registry.currentState = Lifecycle.State.STARTED
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()
        secondOwner.registry.currentState = Lifecycle.State.RESUMED
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldNotBeNull()

        secondHost.removeView(engineWebView)
        engineWebView.releaseResources()
    }

    @Test
    fun onAttachedToWindow_givenSetupRanWhileOwnerAlreadyDestroyed_expectInterfaceAttachedUnderNewOwner() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // The host Fragment's view has already been torn down when the message arrives:
        // setup() finds a DESTROYED owner, which delivers no events, so nothing attaches.
        val deadOwner = FakeLifecycleOwner()
        deadOwner.registry.currentState = Lifecycle.State.RESUMED
        deadOwner.registry.currentState = Lifecycle.State.DESTROYED
        val detachedHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(deadOwner) }
        val engineWebView = EngineWebView(activity)
        detachedHost.addView(engineWebView)

        engineWebView.setup(givenConfiguration())
        val webView = shadowOf(engineWebView.getChildAt(0) as WebView)
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()

        // The Fragment comes back and the same engine is shown under its new owner.
        detachedHost.removeView(engineWebView)
        val liveOwner = FakeLifecycleOwner()
        liveOwner.registry.currentState = Lifecycle.State.RESUMED
        val liveHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(liveOwner) }
        liveHost.addView(engineWebView)
        activity.setContentView(liveHost)

        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldNotBeNull()

        liveHost.removeView(engineWebView)
        engineWebView.releaseResources()
    }

    @Test
    fun onAttachedToWindow_givenStopLoadingAlreadyCalled_expectNoResubscription() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = FakeLifecycleOwner()
        val host = FrameLayout(activity).apply { setViewTreeLifecycleOwner(owner) }
        val engineWebView = EngineWebView(activity)
        host.addView(engineWebView)
        activity.setContentView(host)
        owner.registry.currentState = Lifecycle.State.RESUMED
        engineWebView.setup(givenConfiguration())
        val webView = shadowOf(engineWebView.getChildAt(0) as WebView)
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldNotBeNull()

        // stopLoading() unsubscribes on purpose; a window re-attach must not undo that.
        engineWebView.stopLoading()
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()
        host.removeView(engineWebView)
        host.addView(engineWebView)

        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()
        owner.registry.currentState = Lifecycle.State.STARTED
        owner.registry.currentState = Lifecycle.State.RESUMED
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()

        host.removeView(engineWebView)
        engineWebView.releaseResources()
    }

    @Test
    fun onAttachedToWindow_givenSetupNotCalled_expectNoLifecycleSubscription() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = FakeLifecycleOwner()
        val host = FrameLayout(activity).apply { setViewTreeLifecycleOwner(owner) }
        val engineWebView = EngineWebView(activity)
        host.addView(engineWebView)
        activity.setContentView(host)
        owner.registry.currentState = Lifecycle.State.RESUMED

        // Without a message to render there is no interface to keep attached.
        val webView = shadowOf(engineWebView.getChildAt(0) as WebView)
        webView.getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME).shouldBeNull()

        host.removeView(engineWebView)
        engineWebView.releaseResources()
    }

    @Test
    fun stopLoading_givenOwnerReplacedSinceSetup_expectUnsubscribedFromTheOwnerActuallyObserved() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val firstOwner = FakeLifecycleOwner()
        val firstHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(firstOwner) }
        val engineWebView = EngineWebView(activity)
        firstHost.addView(engineWebView)
        activity.setContentView(firstHost)
        firstOwner.registry.currentState = Lifecycle.State.RESUMED
        engineWebView.setup(givenConfiguration())
        firstOwner.registry.observerCount shouldBeEqualTo 1

        firstHost.removeView(engineWebView)
        firstOwner.registry.observerCount shouldBeEqualTo 0
        val secondOwner = FakeLifecycleOwner()
        secondOwner.registry.currentState = Lifecycle.State.RESUMED
        val secondHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(secondOwner) }
        secondHost.addView(engineWebView)
        activity.setContentView(secondHost)
        secondOwner.registry.observerCount shouldBeEqualTo 1

        engineWebView.stopLoading()

        // Removed from the owner it was actually observing, and nothing lingers on the old one.
        secondOwner.registry.observerCount shouldBeEqualTo 0
        firstOwner.registry.observerCount shouldBeEqualTo 0

        secondHost.removeView(engineWebView)
        engineWebView.releaseResources()
    }

    @Test
    fun releaseResources_givenSubscribedEngine_expectUnsubscribed() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val owner = FakeLifecycleOwner()
        val host = FrameLayout(activity).apply { setViewTreeLifecycleOwner(owner) }
        val engineWebView = EngineWebView(activity)
        host.addView(engineWebView)
        activity.setContentView(host)
        owner.registry.currentState = Lifecycle.State.RESUMED
        engineWebView.setup(givenConfiguration())
        owner.registry.observerCount shouldBeEqualTo 1

        // releaseResources() requires the engine to be off its parent, which also detaches it.
        host.removeView(engineWebView)
        engineWebView.releaseResources()

        // Not re-added by a later window re-attach either: nothing left to keep a bridge for.
        host.addView(engineWebView)
        owner.registry.observerCount shouldBeEqualTo 0
        host.removeView(engineWebView)
    }

    private fun givenConfiguration() = EngineWebConfiguration(
        siteId = String.random,
        dataCenter = String.random,
        messageId = String.random,
        instanceId = String.random,
        endpoint = "https://${String.random}"
    )

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }
}
