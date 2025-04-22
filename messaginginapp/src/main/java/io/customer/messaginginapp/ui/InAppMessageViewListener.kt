package io.customer.messaginginapp.ui

/**
 * Base listener interface to send events from in-app messages view to the host component.
 */
internal interface InAppMessageViewListener {
    fun onViewSizeChanged(width: Int, height: Int) {}
}

/**
 * Listener interface to send events from modal in-app messages view to the host component.
 */
internal interface ModalInAppMessageViewListener : InAppMessageViewListener
