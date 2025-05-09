package io.customer.messaginginapp.ui.bridge

/**
 * Base callback interface to send events from in-app messages view to the host component.
 */
internal interface InAppMessageViewListener {
    fun onViewSizeChanged(width: Int, height: Int) {}
}

/**
 * Callback interface to send events from modal in-app messages view to the host component.
 */
internal interface ModalInAppMessageViewListener : InAppMessageViewListener

/**
 * Callback interface to send events from in-app messages view to the host component.
 */
internal interface InlineInAppMessageViewListener : InAppMessageViewListener {
    fun onLoadingStarted()
    fun onLoadingFinished()
    fun onNoMessageToDisplay()
}
