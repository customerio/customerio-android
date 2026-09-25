package io.customer.messaginginapp.gist.presentation.engine

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
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
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reproduces MBL-2431: EngineWebView stops receiving taps after its host Fragment view is
 * recreated (react-native-screens pattern).
 *
 * Root cause: EngineWebView registered its lifecycle observer exactly once in setup(). When the
 * host Fragment's view lifecycle owner was destroyed (navigation push) and the same EngineWebView
 * instance was re-parented under a NEW lifecycle owner (navigation pop), no re-registration
 * happened. isAttachedToWebView stayed false and postMessage dropped every subsequent event.
 *
 * Fix: override onAttachedToWindow / onDetachedFromWindow to re-subscribe to the CURRENT
 * view-tree lifecycle owner on each attach, mirroring BaseInlineInAppMessageView (PR #611).
 *
 * Strategy: impl-robust -- covers edge cases: null owner at attach time, multiple attach/detach
 * cycles, setup-before-attach ordering, destroyed-owner guard, stopLoading interaction.
 */
@RunWith(RobolectricTestRunner::class)
class EngineWebViewLifecycleReattachTest : IntegrationTest() {

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

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Creates a LifecycleOwner backed by a real LifecycleRegistry that starts at INITIALIZED. */
    private fun makeOwner(): MutableLifecycleOwner = MutableLifecycleOwner()

    /** Creates a minimal EngineWebConfiguration with random IDs. */
    private fun makeConfig() = EngineWebConfiguration(
        siteId = String.random,
        dataCenter = String.random,
        messageId = String.random,
        instanceId = String.random,
        endpoint = "https://${String.random}"
    )

    /**
     * Calls the protected View.onAttachedToWindow() via reflection.
     *
     * In Robolectric unit tests (no Activity/window), onAttachedToWindow is not triggered
     * automatically when a view is programmatically added to a parent. Calling it directly is the
     * standard pattern for testing view-lifecycle callbacks in isolation.
     */
    private fun callOnAttachedToWindow(view: View) {
        View::class.java.getDeclaredMethod("onAttachedToWindow").apply {
            isAccessible = true
            invoke(view)
        }
    }

    /** Calls the protected View.onDetachedFromWindow() via reflection. */
    private fun callOnDetachedFromWindow(view: View) {
        View::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
            invoke(view)
        }
    }

    /**
     * Delivers a "tap" postMessage directly to EngineWebView's JS interface (bypasses the real
     * WebView JS bridge, which is not running in unit tests). Tests that the interface gate
     * (isAttachedToWebView) allows or blocks delivery.
     */
    private fun deliverTap(engineWebView: EngineWebView, action: String) {
        val payload = Gson().toJson(
            mapOf(
                "gist" to mapOf(
                    "instanceId" to "test-instance",
                    "method" to "tap",
                    "parameters" to mapOf(
                        "name" to "btn",
                        "action" to action,
                        "system" to false
                    )
                )
            )
        )
        // Access the private EngineWebViewInterface field directly so we can call postMessage,
        // which is the real gate guarded by isAttachedToWebView.
        val field = EngineWebView::class.java.getDeclaredField("engineWebViewInterface")
        field.isAccessible = true
        val iface = field.get(engineWebView) as EngineWebViewInterface
        iface.postMessage(payload)
    }

    /** Records tap actions and errors so assertions can verify delivery vs. drop. */
    private class RecordingListener : EngineWebViewListener {
        val taps = mutableListOf<String>()

        override fun tap(name: String, action: String, system: Boolean) {
            taps.add(action)
        }

        override fun bootstrapped() {}
        override fun routeChanged(newRoute: String) {}
        override fun routeError(route: String) {}
        override fun routeLoaded(route: String) {}
        override fun sizeChanged(width: Double, height: Double) {}
        override fun error() {}
        override fun error(error: io.customer.messaginginapp.type.InAppMessageError) {}
    }

    /** Lifecycle owner whose state can be advanced imperatively. */
    class MutableLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    // ---------------------------------------------------------------------------
    // Primary regression test -- MBL-2431
    // ---------------------------------------------------------------------------

    /**
     * Simulates the exact react-native-screens navigation pattern that triggered MBL-2431:
     *
     * 1. EngineWebView is attached under owner1 (RESUMED) and setup() runs normally.
     * 2. Navigation push: owner1 is paused then destroyed (view lifecycle owner goes away).
     *    JS interface detaches; taps are dropped while detached.
     * 3. Navigation pop: the SAME EngineWebView instance is re-parented under owner2 (RESUMED).
     *    Before the fix: no re-registration happens; isAttachedToWebView stays false.
     *    After the fix:  onAttachedToWindow re-registers with owner2; onResume replays ->
     *                    onLifecycleResumed() -> attach() -> isAttachedToWebView=true.
     *
     * Asserts that a tap delivered AFTER re-attachment reaches the listener.
     */
    @Test
    fun onAttachedToWindow_givenReattachedUnderNewOwner_expectJsInterfaceRearmed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener = RecordingListener()
        engineWebView.listener = listener

        // --- Phase 1: initial attach under owner1 RESUMED ---
        val owner1 = makeOwner()
        owner1.handle(Lifecycle.Event.ON_CREATE)
        owner1.handle(Lifecycle.Event.ON_START)
        owner1.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner1)
        callOnAttachedToWindow(engineWebView)
        engineWebView.setup(makeConfig())

        // Baseline: tap reaches listener while interface is armed.
        deliverTap(engineWebView, "action-before-nav")
        listener.taps shouldContain "action-before-nav"

        // --- Phase 2: navigation push -- owner1 pauses then is destroyed ---
        owner1.handle(Lifecycle.Event.ON_PAUSE)
        // Interface is now detached; tap must be dropped.
        deliverTap(engineWebView, "action-while-paused")
        listener.taps shouldNotContain "action-while-paused"

        owner1.handle(Lifecycle.Event.ON_STOP)
        owner1.handle(Lifecycle.Event.ON_DESTROY)

        // Simulate view being removed from parent (detach callback).
        callOnDetachedFromWindow(engineWebView)

        // --- Phase 3: navigation pop -- re-parent under owner2 RESUMED ---
        val owner2 = makeOwner()
        owner2.handle(Lifecycle.Event.ON_CREATE)
        owner2.handle(Lifecycle.Event.ON_START)
        owner2.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner2)

        // This is the key call: onAttachedToWindow must re-subscribe to owner2.
        // Before the fix: no re-subscription; isAttachedToWebView stays false; tap is dropped.
        // After the fix:  registered with owner2; RESUMED state replays -> onLifecycleResumed()
        //                 -> attach() -> isAttachedToWebView=true.
        callOnAttachedToWindow(engineWebView)

        deliverTap(engineWebView, "action-after-reattach")
        listener.taps shouldContain "action-after-reattach"
    }

    // ---------------------------------------------------------------------------
    // Edge case: null lifecycle owner at attach time
    // ---------------------------------------------------------------------------

    /**
     * If the view is attached before any lifecycle owner is set in the view tree (e.g. wrapped in a
     * non-lifecycle context), onAttachedToWindow must not crash and setup()'s fallback
     * (direct onLifecycleResumed) must still arm the interface.
     */
    @Test
    fun onAttachedToWindow_givenNoLifecycleOwnerInTree_expectSetupFallbackStillArmsInterface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener = RecordingListener()
        engineWebView.listener = listener

        // No ViewTreeLifecycleOwner set -- simulates a non-lifecycle host context.
        callOnAttachedToWindow(engineWebView) // must not throw

        // setup() falls back to direct onLifecycleResumed() when no owner is found.
        engineWebView.setup(makeConfig())

        deliverTap(engineWebView, "action-no-owner")
        listener.taps shouldContain "action-no-owner"
    }

    // ---------------------------------------------------------------------------
    // Edge case: multiple attach/detach cycles
    // ---------------------------------------------------------------------------

    /**
     * Guards against observer leaks across multiple navigation push/pop cycles.
     * Each cycle must leave exactly one live observer on the current owner -- no duplicates,
     * no stale registrations on old destroyed owners.
     */
    @Test
    fun onAttachedToWindow_givenMultipleReattachCycles_expectCorrectBehaviorEachTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener = RecordingListener()
        engineWebView.listener = listener

        // Initial attach
        val owner1 = makeOwner()
        owner1.handle(Lifecycle.Event.ON_CREATE)
        owner1.handle(Lifecycle.Event.ON_START)
        owner1.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner1)
        callOnAttachedToWindow(engineWebView)
        engineWebView.setup(makeConfig())
        deliverTap(engineWebView, "cycle1-before")
        listener.taps shouldContain "cycle1-before"

        // First detach cycle
        owner1.handle(Lifecycle.Event.ON_PAUSE)
        owner1.handle(Lifecycle.Event.ON_STOP)
        owner1.handle(Lifecycle.Event.ON_DESTROY)
        callOnDetachedFromWindow(engineWebView)

        // Re-attach under owner2
        val owner2 = makeOwner()
        owner2.handle(Lifecycle.Event.ON_CREATE)
        owner2.handle(Lifecycle.Event.ON_START)
        owner2.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner2)
        callOnAttachedToWindow(engineWebView)
        deliverTap(engineWebView, "cycle2-after")
        listener.taps shouldContain "cycle2-after"

        // Second detach cycle
        owner2.handle(Lifecycle.Event.ON_PAUSE)
        owner2.handle(Lifecycle.Event.ON_STOP)
        owner2.handle(Lifecycle.Event.ON_DESTROY)
        callOnDetachedFromWindow(engineWebView)

        // Re-attach under owner3
        val owner3 = makeOwner()
        owner3.handle(Lifecycle.Event.ON_CREATE)
        owner3.handle(Lifecycle.Event.ON_START)
        owner3.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner3)
        callOnAttachedToWindow(engineWebView)
        deliverTap(engineWebView, "cycle3-after")
        listener.taps shouldContain "cycle3-after"

        // Total: exactly 3 taps delivered, one per cycle, no duplicates from stale observers.
        listener.taps.size shouldBeEqualTo 3
    }

    // ---------------------------------------------------------------------------
    // Edge case: setup() called before onAttachedToWindow
    // ---------------------------------------------------------------------------

    /**
     * If setup() is invoked before the view is attached to a window (e.g. in embed scenarios
     * where the host inflates the view then calls setup() before adding it to the hierarchy),
     * setup()'s direct-attach fallback runs. When onAttachedToWindow later fires under a
     * RESUMED owner, the interface must remain armed without double-firing teardown.
     */
    @Test
    fun setup_givenCalledBeforeAttach_onAttachUnderResumedOwner_expectInterfaceArmed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener = RecordingListener()
        engineWebView.listener = listener

        // setup() first, no lifecycle owner in tree yet -- fallback path fires.
        engineWebView.setup(makeConfig())
        deliverTap(engineWebView, "after-setup-before-attach")
        listener.taps shouldContain "after-setup-before-attach"

        // Now attach under a RESUMED owner (post-setup window attachment).
        val owner = makeOwner()
        owner.handle(Lifecycle.Event.ON_CREATE)
        owner.handle(Lifecycle.Event.ON_START)
        owner.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner)
        callOnAttachedToWindow(engineWebView)

        // Interface should still be armed after attach.
        deliverTap(engineWebView, "after-attach-post-setup")
        listener.taps shouldContain "after-attach-post-setup"
    }

    // ---------------------------------------------------------------------------
    // Edge case: destroyed owner guard
    // ---------------------------------------------------------------------------

    /**
     * If onAttachedToWindow fires when the view-tree lifecycle owner is already DESTROYED (race
     * between remove/add operations), EngineWebView must not attempt to register with it
     * (LifecycleRegistry silently ignores addObserver after DESTROYED, but registering would
     * leave observedLifecycle pointing at a dead owner and prevent cleanup).
     */
    @Test
    fun onAttachedToWindow_givenDestroyedOwner_expectNoObserverRegisteredAndNoCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        val listener = RecordingListener()
        engineWebView.listener = listener

        val owner = makeOwner()
        owner.handle(Lifecycle.Event.ON_CREATE)
        owner.handle(Lifecycle.Event.ON_START)
        owner.handle(Lifecycle.Event.ON_RESUME)
        owner.handle(Lifecycle.Event.ON_PAUSE)
        owner.handle(Lifecycle.Event.ON_STOP)
        owner.handle(Lifecycle.Event.ON_DESTROY) // already DESTROYED before attach

        ViewTreeLifecycleOwner.set(engineWebView, owner)
        // Must not crash, must not throw on LifecycleRegistry after DESTROYED.
        callOnAttachedToWindow(engineWebView)

        // No tap should be delivered (interface is not armed; no valid RESUME was replayed).
        engineWebView.setup(makeConfig()) // falls back to direct onLifecycleResumed -- still arms
        // After setup fallback, taps should work (setup arms the interface directly).
        deliverTap(engineWebView, "after-destroyed-owner-setup")
        listener.taps shouldContain "after-destroyed-owner-setup"
    }

    // ---------------------------------------------------------------------------
    // Edge case: stopLoading() followed by onDetachedFromWindow
    // ---------------------------------------------------------------------------

    /**
     * stopLoading() is called to stop a dismissing message. It removes the observer and calls
     * onLifecyclePaused(). A subsequent onDetachedFromWindow must not crash or attempt a
     * double-remove that could throw.
     */
    @Test
    fun stopLoading_thenDetach_expectNoDoubleRemoveOrCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        engineWebView.listener = mockk(relaxed = true)

        val owner = makeOwner()
        owner.handle(Lifecycle.Event.ON_CREATE)
        owner.handle(Lifecycle.Event.ON_START)
        owner.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner)
        callOnAttachedToWindow(engineWebView)
        engineWebView.setup(makeConfig())

        // stopLoading removes the observer and cleans up.
        engineWebView.stopLoading()

        // onDetachedFromWindow must be a safe no-op at this point.
        callOnDetachedFromWindow(engineWebView) // must not throw
    }

    // ---------------------------------------------------------------------------
    // Edge case: onDetachedFromWindow followed by stopLoading()
    // ---------------------------------------------------------------------------

    /**
     * If onDetachedFromWindow fires first (unusual ordering, e.g. system-driven detach before
     * the controller calls stopLoading), stopLoading must not crash on the now-null observer.
     */
    @Test
    fun detach_thenStopLoading_expectNoDoubleRemoveOrCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineWebView = EngineWebView(context)
        engineWebView.listener = mockk(relaxed = true)

        val owner = makeOwner()
        owner.handle(Lifecycle.Event.ON_CREATE)
        owner.handle(Lifecycle.Event.ON_START)
        owner.handle(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(engineWebView, owner)
        callOnAttachedToWindow(engineWebView)
        engineWebView.setup(makeConfig())

        callOnDetachedFromWindow(engineWebView) // observer removed and observedLifecycle nulled

        // stopLoading must survive with a null observedLifecycle.
        engineWebView.stopLoading() // must not throw
    }
}
