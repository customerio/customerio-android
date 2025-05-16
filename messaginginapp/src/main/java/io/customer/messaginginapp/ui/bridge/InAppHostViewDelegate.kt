package io.customer.messaginginapp.ui.bridge

import android.view.ViewGroup
import androidx.core.view.isVisible
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView

/**
 * Delegate interface that abstracts host view operations for in-app messages.
 * Allows operations like adding/removing child views, creating new view instances,
 * and posting actions on UI thread without directly depending on Android framework.
 * Designed for easier testing and mocking.
 */
@InternalCustomerIOApi
interface InAppHostViewDelegate {
    var isVisible: Boolean

    fun addView(delegate: EngineWebViewDelegate)
    fun removeView(delegate: EngineWebViewDelegate)
    fun createEngineWebViewInstance(): EngineWebViewDelegate
    fun post(action: () -> Unit)
}

/**
 * Default implementation of [InAppHostViewDelegate] that wraps a real Android ViewGroup.
 * Simplifies implementation by providing a concrete way to manage child views and UI actions.
 */
internal class InAppHostViewDelegateImpl(
    private val view: ViewGroup
) : InAppHostViewDelegate {
    override var isVisible: Boolean
        get() = view.isVisible
        set(value) {
            view.isVisible = value
        }

    override fun addView(delegate: EngineWebViewDelegate) {
        view.addView(delegate.getView())
    }

    override fun removeView(delegate: EngineWebViewDelegate) {
        view.removeView(delegate.getView())
    }

    override fun createEngineWebViewInstance(): EngineWebViewDelegate {
        return EngineWebView(view.context)
    }

    override fun post(action: () -> Unit) {
        view.post(action)
    }
}
