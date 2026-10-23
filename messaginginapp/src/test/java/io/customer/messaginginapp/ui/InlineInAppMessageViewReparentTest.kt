package io.customer.messaginginapp.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.google.gson.Gson
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.extensions.attachToSDKComponent
import io.customer.commontest.util.ScopeProviderStub
import io.customer.messaginginapp.MessagingInAppModuleConfig
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.listeners.GistQueue
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistProvider
import io.customer.messaginginapp.gist.presentation.GistSdk
import io.customer.messaginginapp.gist.presentation.PollingLifecycleManager
import io.customer.messaginginapp.gist.presentation.SseLifecycleManager
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewInterface
import io.customer.messaginginapp.state.InAppMessagingAction
import io.customer.messaginginapp.state.InAppMessagingManager
import io.customer.messaginginapp.state.MessageBuilderMock
import io.customer.messaginginapp.testutils.core.IntegrationTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.ScopeProvider
import io.customer.sdk.data.model.Region
import io.customer.sdk.lifecycle.CustomerIOActivityLifecycleCallbacks
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * End to end: an [InlineInAppMessageView] that is moved from one lifecycle owner to another, the
 * way react-native-screens re-parents the screen below a push when it is popped, must keep
 * delivering renderer taps to the store.
 */
@RunWith(RobolectricTestRunner::class)
class InlineInAppMessageViewReparentTest : IntegrationTest() {

    private val scopeProviderStub = ScopeProviderStub.Unconfined()
    private val moduleConfig = MessagingInAppModuleConfig.Builder(
        siteId = "test-site-id",
        region = Region.US
    ).build()

    private lateinit var gistProvider: GistProvider
    private lateinit var messagingManager: InAppMessagingManager

    override fun setup(testConfig: TestConfig) {
        super.setup(
            testConfigurationDefault {
                diGraph {
                    sdk {
                        overrideDependency<GistQueue>(mockk(relaxed = true))
                        overrideDependency<ScopeProvider>(scopeProviderStub)
                        overrideDependency(
                            mockk<CustomerIOActivityLifecycleCallbacks>(relaxed = true) {
                                every { subscribe(any()) } just Runs
                            }
                        )
                        overrideDependency(mockk<PollingLifecycleManager>(relaxed = true))
                        overrideDependency(mockk<SseLifecycleManager>(relaxed = true))
                    }
                }
            }
        )

        ModuleMessagingInApp(config = moduleConfig).attachToSDKComponent()
        gistProvider = GistSdk(
            siteId = moduleConfig.siteId,
            dataCenter = moduleConfig.region.code,
            environment = GistEnvironment.LOCAL
        ).also { SDKComponent.overrideDependency<GistProvider>(it) }
        messagingManager = spyk(SDKComponent.inAppMessagingManager)
            .also { SDKComponent.overrideDependency<InAppMessagingManager>(it) }

        messagingManager.dispatch(
            InAppMessagingAction.Initialize(
                siteId = moduleConfig.siteId,
                dataCenter = moduleConfig.region.code,
                environment = GistEnvironment.LOCAL
            )
        )
        messagingManager.dispatch(InAppMessagingAction.SetUserIdentifier("test-user-id"))
    }

    override fun teardown() {
        gistProvider.reset()
        super.teardown()
    }

    @Test
    fun tap_givenViewMovedToNewLifecycleOwner_expectTapReachesStore() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val firstOwner = FakeLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val firstHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(firstOwner) }
        activity.setContentView(firstHost)
        val inlineView = InlineInAppMessageView(activity).apply { elementId = ELEMENT_ID }
        firstHost.addView(inlineView)

        val message = MessageBuilderMock.createMessage(elementId = ELEMENT_ID, routeRule = null)
        messagingManager.dispatch(InAppMessagingAction.SetPageRoute(ROUTE))
        messagingManager.dispatch(InAppMessagingAction.EmbedMessages(listOf(message)))
        idleMainLooper()
        val bridge = bridgeOf(inlineView)
        bridge.postMessage(event(message, "routeLoaded", mapOf("route" to ROUTE)))
        idleMainLooper()

        bridge.postMessage(tapEvent(message))
        idleMainLooper()
        verify(exactly = 1) { messagingManager.dispatch(match { it is InAppMessagingAction.EngineAction.Tap }) }

        // Push: the screen below pauses, its view is removed, and its view lifecycle is destroyed.
        firstOwner.registry.currentState = Lifecycle.State.STARTED
        firstHost.removeView(inlineView)
        firstOwner.registry.currentState = Lifecycle.State.DESTROYED

        // Pop: the same view comes back under a new view lifecycle owner.
        val secondOwner = FakeLifecycleOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val secondHost = FrameLayout(activity).apply { setViewTreeLifecycleOwner(secondOwner) }
        activity.setContentView(secondHost)
        secondHost.addView(inlineView)
        idleMainLooper()

        bridge.postMessage(tapEvent(message))
        idleMainLooper()
        verify(exactly = 2) { messagingManager.dispatch(match { it is InAppMessagingAction.EngineAction.Tap }) }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    /** The engine's JavaScript bridge; the same object survives the move, only its attachment changes. */
    private fun bridgeOf(inlineView: View): EngineWebViewInterface {
        val engine = requireNotNull(findEngine(inlineView)) { "no EngineWebView embedded" }
        val webView = engine.getChildAt(0) as WebView
        return shadowOf(webView).getJavascriptInterface(EngineWebViewInterface.JAVASCRIPT_INTERFACE_NAME)
            as EngineWebViewInterface
    }

    private fun findEngine(view: View): EngineWebView? {
        if (view is EngineWebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findEngine(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun tapEvent(message: Message) =
        event(message, "tap", mapOf("action" to "demo-tap", "name" to "Primary", "system" to false))

    private fun event(message: Message, method: String, parameters: Map<String, Any>): String =
        Gson().toJson(
            mapOf("gist" to mapOf("instanceId" to message.instanceId, "method" to method, "parameters" to parameters))
        )

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private companion object {
        const val ELEMENT_ID = "reparent-element"
        const val ROUTE = "home"
    }
}
