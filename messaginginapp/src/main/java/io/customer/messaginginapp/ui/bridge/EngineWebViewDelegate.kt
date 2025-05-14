package io.customer.messaginginapp.ui.bridge

import android.view.View
import io.customer.messaginginapp.gist.data.model.engine.EngineWebConfiguration
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView
import io.customer.messaginginapp.gist.presentation.engine.EngineWebViewListener

import io.customer.base.internal.InternalCustomerIOApi

/**
 * Delegate interface to decouple [EngineWebView] from its consumers.
 * Provides a minimal set of operations to control and configure the view without directly
 * depending on its implementation.
 * Useful for testing, mocking, or swapping the view behind the interface.
 */
@InternalCustomerIOApi
interface EngineWebViewDelegate {
    var listener: EngineWebViewListener?

    fun setup(configuration: EngineWebConfiguration)
    fun stopLoading()
    fun releaseResources()
    fun getView(): View
    fun setAlpha(alpha: Float)
    fun bringToFront()
}
