package io.customer.messaginginapp.ui

/**
 * Listener interface to send events from the InAppMessageHostView to the host activity or fragment.
 */
internal interface InAppMessageViewEventsListener {
    fun onViewSizeChanged(width: Int, height: Int) {}
}
