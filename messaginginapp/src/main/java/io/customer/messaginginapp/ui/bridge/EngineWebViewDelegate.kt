package io.customer.messaginginapp.ui.bridge

import android.view.View
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewListener

/**
 * Delegate interface to decouple [EngineWebView] from its consumers.
 * Provides a minimal set of operations to control and configure the view without directly
 * depending on its implementation.
 * Useful for testing, mocking, or swapping the view behind the interface.
 */
internal interface EngineWebViewDelegate {
    var listener: EngineWebViewListener?

    fun setup(configuration: EngineWebConfiguration)
    fun stopLoading()
    fun releaseResources()
    fun getView(): View
    fun setAlpha(alpha: Float)
    fun bringToFront()

    /**
     * Sets the lifecycle provider for this delegate.
     * Should be called before setup() to ensure proper lifecycle management.
     *
     * @param lifecycleProvider The lifecycle provider to use
     */
    fun setLifecycleProvider(lifecycleProvider: io.customer.messaginginapp.ui.lifecycle.LifecycleProvider)
}
