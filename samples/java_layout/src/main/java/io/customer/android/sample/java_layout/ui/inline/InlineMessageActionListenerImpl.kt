package io.customer.android.sample.java_layout.ui.inline

import android.content.Context
import android.util.Log
import android.widget.Toast
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener

/**
 * Implementation of InlineMessageActionListener that logs actions and shows toasts.
 * This is a sample implementation to demonstrate the use of the listener.
 */
class InlineMessageActionListenerImpl(
    private val context: Context,
    private val source: String = "Default"
) : InlineMessageActionListener {

    private val TAG = "InlineMessageListener"

    override fun onActionClick(message: InAppMessage, actionValue: String, actionName: String) {
        // Log the action click
        Log.d(TAG, "[$source] Action clicked: $actionName with value: $actionValue for message: ${message.messageId}")

        // Show a toast to the user
        val toastMessage = "$source Action: $actionName\nValue: $actionValue"
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}
