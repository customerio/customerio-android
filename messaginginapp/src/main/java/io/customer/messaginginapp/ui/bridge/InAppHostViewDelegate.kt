package io.customer.messaginginapp.ui.bridge

import android.view.ViewGroup
import androidx.core.view.isVisible
import io.customer.messaginginapp.gist.presentation.engine.EngineWebView

/**
 * Delegate interface that abstracts host view operations for in-app messages.
 * Allows operations like adding/removing child views, creating new view instances,
 * and posting actions on UI thread without directly depending on Android framework.
 * Designed for easier testing and mocking.
 */
internal interface InAppHostViewDelegate {
    fun addChildView(delegate: EngineWebViewDelegate)
    fun removeChildView(delegate: EngineWebViewDelegate)
    fun createEngineWebViewInstance(): EngineWebViewDelegate
    fun postOnUIThread(action: () -> Unit)
    fun setVisibility(visible: Boolean)
}

/**
 * Default implementation of [InAppHostViewDelegate] that wraps a real Android ViewGroup.
 * Simplifies implementation by providing a concrete way to manage child views and UI actions.
 */
internal class InAppHostViewDelegateImpl(
    private val view: ViewGroup
) : InAppHostViewDelegate {
    override fun addChildView(delegate: EngineWebViewDelegate) {
        view.addView(delegate.getView())
    }

    override fun removeChildView(delegate: EngineWebViewDelegate) {
        view.removeView(delegate.getView())
    }

    override fun createEngineWebViewInstance(): EngineWebViewDelegate {
        return EngineWebView(view.context)
    }

    override fun postOnUIThread(action: () -> Unit) {
        view.post(action)
    }

    override fun setVisibility(visible: Boolean) {
        view.isVisible = visible
    }
}
