package io.customer.android.sample.java_layout.sdk;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.customer.android.sample.java_layout.utils.Logger;
import io.customer.messaginginapp.type.InAppEventListener;
import io.customer.messaginginapp.type.InAppMessage;
import io.customer.sdk.CustomerIO;

public class InAppMessageEventListener implements InAppEventListener {
    private final Logger logger;

    public InAppMessageEventListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void messageShown(@NonNull InAppMessage message) {
        logger.v(String.format(Locale.ENGLISH, "in-app message shown. message: %s", message));
        trackInAppEvent("messageShown", message);
    }

    @Override
    public void messageDismissed(@NonNull InAppMessage message) {
        logger.v(String.format(Locale.ENGLISH, "in-app message dismissed. message: %s", message));
        trackInAppEvent("messageDismissed", message);
    }

    @Override
    public void errorWithMessage(@NonNull InAppMessage message) {
        logger.v(String.format(Locale.ENGLISH, "error with in-app message. message: %s", message));
        trackInAppEvent("errorWithMessage", message);
    }

    @Override
    public void messageActionTaken(@NonNull InAppMessage message, @NonNull String actionValue, @NonNull String actionName) {
        logger.v(String.format(Locale.ENGLISH, "in-app message action taken. action: %s, name: %s, message: %s", actionValue, actionName, message));
        trackInAppEvent("messageActionTaken",
                message,
                new HashMap<String, String>() {{
                    put("action-value", actionValue);
                    put("action-name", actionName);
                }});
    }

    private void trackInAppEvent(@NonNull String eventName, @NonNull InAppMessage message) {
        trackInAppEvent(eventName, message, null);
    }

    private void trackInAppEvent(@NonNull String eventName, @NonNull InAppMessage message, @Nullable Map<String, String> arguments) {
        CustomerIO.instance().track(
                "inapp action",
                new HashMap<String, String>() {{
                    if (arguments != null) {
                        putAll(arguments);
                    }
                    put("event-name", eventName);
                    put("message-id", message.getMessageId());
                    String deliveryId = message.getDeliveryId();
                    put("delivery-id", deliveryId == null ? "NULL" : deliveryId);
                }}
        );
    }
}
