package io.customer.messaginginapp.ui.bridge

/**
 * Base callback interface to send events from in-app messages view to the host component.
 */
internal interface InAppMessageViewCallback {
    fun onViewSizeChanged(width: Int, height: Int) {}
}

/**
 * Callback interface to send events from modal in-app messages view to the host component.
 */
internal interface ModalInAppMessageViewCallback : InAppMessageViewCallback

/**
 * Callback interface to send events from in-app messages view to the host component.
 */
internal interface InlineInAppMessageViewCallback : InAppMessageViewCallback {
    fun onLoadingStarted()
    fun onLoadingFinished()
    fun onNoMessageToDisplay()
}
