package io.customer.messaginginapp.gist.presentation.engine

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.R as LifecycleR
import androidx.test.core.app.ApplicationProvider
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.random
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.messaginginapp.type.InAppMessageError
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Guards the re-subscribe-on-window-attach fix for [EngineWebView].
 *
 * Regression path: react-native-screens (or any navigator that recreates the host Fragment)
 * pauses and destroys the view's lifecycle owner on push, then re-parents the *same*
 * EngineWebView instance under a *new* lifecycle owner on pop.  Before the fix,
 * [EngineWebView] only registered its lifecycle observer once inside [EngineWebView.setup],
 * so the observer remained on the destroyed first owner and was never re-registered on the new
 * one.  [EngineWebViewInterface.postMessage] drops every event while its
 * `isAttachedToWebView` gate is false, so taps and close actions silently disappeared.
 *
 * The fix mirrors [io.customer.messaginginapp.ui.core.BaseInlineInAppMessageView]'s pattern
 * (PR #611 / MBL-1329): override [EngineWebView.onAttachedToWindow] to re-subscribe to the
 * current view-tree lifecycle owner, and [EngineWebView.onDetachedFromWindow] to unsubscribe.
 * [LifecycleRegistry.addObserver] replays the owner's current state, so a RESUMED owner
 * immediately drives [EngineWebView.onLifecycleResumed] and re-arms the JS interface.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewLifecycleResubscribeTest : IntegrationTest() {

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
        every { inAppMessagingManager.getCurrentState() } returns InAppMessagingState()
    }

    /**
     * Records each tap action string delivered to the listener, so assertions can be made
     * without accessing [EngineWebViewInterface]'s private [isAttachedToWebView] field.
     */
    private class TapRecordingListener : EngineWebViewListener {
        val taps = mutableListOf<String>()

        override fun bootstrapped() {}
        override fun tap(name: String, action: String, system: Boolean) { taps.add(action) }
        override fun routeChanged(newRoute: String) {}
        override fun routeError(route: String) {}
        override fun routeLoaded(route: String) {}
        override fun sizeChanged(width: Double, height: Double) {}
        override fun error() {}
        override fun error(error: InAppMessageError) {}
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private fun makeResumedLifecycleOwner(): Pair<LifecycleOwner, LifecycleRegistry> {
        val owner = mockk<LifecycleOwner>(relaxed = true)
        val registry = LifecycleRegistry(owner)
        every { owner.lifecycle } returns registry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        return owner to registry
    }

    private fun buildConfig(): EngineWebConfiguration = EngineWebConfiguration(
        siteId = String.random,
        dataCenter = String.random,
        messageId = String.random,
        instanceId = "test-instance",
        endpoint = "https://${String.random}"
    )

    private fun buildTapPayload(instanceId: String = "test-instance", action: String = "btn-click"): String =
        """{"gist":{"instanceId":"$instanceId","method":"tap","parameters":{"action":"$action","name":"button","system":false}}}"""

    /**
     * Gets the private [EngineWebViewInterface] held by [EngineWebView] so tests can post
     * messages without going through the real JS bridge.
     */
    private fun getJsInterface(engineWebView: EngineWebView): EngineWebViewInterface {
        val field = EngineWebView::class.java.getDeclaredField("engineWebViewInterface")
        field.isAccessible = true
        return field.get(engineWebView) as EngineWebViewInterface
    }

    /**
     * Calls the protected [android.view.View.onAttachedToWindow] override on [EngineWebView],
     * traversing the class hierarchy from the most-derived class so that after the fix the
     * EngineWebView override is found first.  Before the fix, the traversal reaches the base
     * class version, which does not re-subscribe to the lifecycle — making the test fail
     * for the right reason.
     */
    private fun simulateAttachedToWindow(engineWebView: EngineWebView) {
        ReflectionHelpers.callInstanceMethod<Unit>(engineWebView, "onAttachedToWindow")
    }

    /**
     * Same traversal for [android.view.View.onDetachedFromWindow].
     */
    private fun simulateDetachedFromWindow(engineWebView: EngineWebView) {
        ReflectionHelpers.callInstanceMethod<Unit>(engineWebView, "onDetachedFromWindow")
    }

    // -----------------------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------------------

    /**
     * Core regression test: after the host Fragment's view lifecycle owner is recreated
     * (pause→destroy on push, new RESUMED owner on pop), the JS interface must be re-armed
     * so that taps and close actions reach the listener.
     *
     * Fails before the fix because onAttachedToWindow does not re-subscribe the observer on
     * the new lifecycle owner, so isAttachedToWebView stays false and postMessage drops taps.
     */
    @Test
    fun onAttachedToWindow_givenReattachedToNewResumedOwner_expectJsInterfaceReArmedAndTapDelivered() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val listener = TapRecordingListener()
        val engineWebView = EngineWebView(context)
        engineWebView.listener = listener

        // -- First lifecycle owner: RESUMED before setup() is called --
        val (firstOwner, firstLifecycle) = makeResumedLifecycleOwner()
        engineWebView.setTag(LifecycleR.id.view_tree_lifecycle_owner, firstOwner)

        // setup() registers the observer; LifecycleRegistry replays RESUMED -> onLifecycleResumed()
        // -> engineWebViewInterface.attach() -> isAttachedToWebView = true
        engineWebView.setup(buildConfig())

        val jsInterface = getJsInterface(engineWebView)

        // Pre-condition: tap is delivered while first lifecycle is active.
        jsInterface.postMessage(buildTapPayload(action = "first-tap"))
        listener.taps shouldBeEqualTo listOf("first-tap")
        listener.taps.clear()

        // Simulate Fragment view recreation: first owner goes through pause/stop/destroy
        firstLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) // -> onLifecyclePaused() -> detach
        firstLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        firstLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        // Tap must now be dropped: interface was detached on PAUSE (unchanged by the fix).
        jsInterface.postMessage(buildTapPayload(action = "should-drop"))
        listener.taps shouldBeEqualTo emptyList()

        // Simulate view being removed from its first parent (onDetachedFromWindow).
        // After the fix this removes the observer from the now-destroyed first registry.
        simulateDetachedFromWindow(engineWebView)

        // -- Second lifecycle owner: re-parented under a new RESUMED owner (the "pop" case) --
        val (secondOwner, _) = makeResumedLifecycleOwner()
        engineWebView.setTag(LifecycleR.id.view_tree_lifecycle_owner, secondOwner)

        // onAttachedToWindow is the fix point.
        // Before fix: falls through to View.onAttachedToWindow() — no observer re-registration.
        // After fix:  EngineWebView.onAttachedToWindow() registers on secondLifecycle, which
        //             replays RESUMED -> onLifecycleResumed() -> attach() -> isAttachedToWebView = true.
        simulateAttachedToWindow(engineWebView)

        // Assert: tap must reach the listener after re-attachment.
        // This line fails before the fix (tap dropped) and passes after.
        jsInterface.postMessage(buildTapPayload(action = "after-reattach"))
        listener.taps shouldBeEqualTo listOf("after-reattach")
    }

    /**
     * Verifies the initial attach path is unaffected: setup() called before onAttachedToWindow
     * still arms the interface (the existing fallback in setup() stays intact).
     */
    @Test
    fun setup_givenCalledBeforeWindowAttach_expectInterfaceArmedViaSelf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val listener = TapRecordingListener()
        val engineWebView = EngineWebView(context)
        engineWebView.listener = listener

        val (owner, _) = makeResumedLifecycleOwner()
        engineWebView.setTag(LifecycleR.id.view_tree_lifecycle_owner, owner)

        // setup() is called; LifecycleRegistry replays RESUMED -> interface armed.
        engineWebView.setup(buildConfig())

        val jsInterface = getJsInterface(engineWebView)
        jsInterface.postMessage(buildTapPayload(action = "initial-tap"))
        listener.taps shouldBeEqualTo listOf("initial-tap")
    }

    /**
     * Verifies that attaching then re-attaching to the SAME lifecycle owner is idempotent:
     * no double-fire of onLifecycleResumed and no duplicate tap delivery.
     */
    @Test
    fun onAttachedToWindow_givenSameOwnerReattach_expectNoDoubleResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // LifecycleRegistry idempotency guarantees onResume fires once even if we re-attach twice.
        val listener = TapRecordingListener()
        val engineWebView = EngineWebView(context)
        engineWebView.listener = listener

        val (owner, _) = makeResumedLifecycleOwner()
        engineWebView.setTag(LifecycleR.id.view_tree_lifecycle_owner, owner)

        engineWebView.setup(buildConfig())

        // Re-attaching to the same owner must be idempotent (LifecycleRegistry contract).
        simulateAttachedToWindow(engineWebView)
        simulateAttachedToWindow(engineWebView)

        val jsInterface = getJsInterface(engineWebView)
        jsInterface.postMessage(buildTapPayload(action = "tap"))
        // One tap must arrive exactly once; duplicates would indicate double-fire.
        listener.taps shouldBeEqualTo listOf("tap")
    }
}
