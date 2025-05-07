package io.customer.android.sample.java_layout.ui.inline;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import io.customer.messaginginapp.type.InAppMessage;
import io.customer.messaginginapp.type.InlineMessageActionListener;

/**
 * Java implementation of InlineMessageActionListener.
 * This demonstrates how to implement the listener in Java.
 */
public class JavaInlineMessageActionListener implements InlineMessageActionListener {
    private static final String TAG = "JavaInlineListener";
    private final Context context;
    private final String source;

    public JavaInlineMessageActionListener(Context context, String source) {
        this.context = context;
        this.source = source;
    }

    @Override
    public void onActionClick(InAppMessage message, String actionValue, String actionName) {
        // Log the action click
        Log.d(TAG, "[" + source + "] Action clicked: " + actionName + 
                " with value: " + actionValue + 
                " for message: " + message.getMessageId());
        
        // Show a toast to the user
        String toastMessage = source + " Action: " + actionName + "\nValue: " + actionValue;
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show();
    }
}