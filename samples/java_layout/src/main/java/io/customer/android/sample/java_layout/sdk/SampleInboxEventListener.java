package io.customer.android.sample.java_layout.sdk;

import androidx.annotation.NonNull;

import java.util.Locale;

import io.customer.android.sample.java_layout.utils.Logger;
import io.customer.messaginginapp.type.InboxActionMessage;
import io.customer.messaginginapp.type.InboxEventListener;

/**
 * Sample implementation for {@link InboxEventListener}.
 * <p>
 * Observational: it logs each callback and returns {@code false} from
 * {@link #messageActionTaken} so the SDK still applies its default action handling (e.g. opening an
 * http(s) url). Return {@code true} instead to fully handle the action and suppress that default.
 */
public class SampleInboxEventListener implements InboxEventListener {
    private final Logger logger;

    public SampleInboxEventListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean messageActionTaken(@NonNull InboxActionMessage message, @NonNull String actionName, @NonNull String actionValue) {
        logEvent("messageActionTaken. name: %s, value: %s, message: %s", actionName, actionValue, message.getMessageId());
        return false;
    }

    @Override
    public void messageShown(@NonNull InboxActionMessage message) {
        logEvent("messageShown. message: %s", message.getMessageId());
    }

    @Override
    public void messageOpened(@NonNull InboxActionMessage message) {
        logEvent("messageOpened. message: %s", message.getMessageId());
    }

    @Override
    public void messageDismissed(@NonNull InboxActionMessage message) {
        logEvent("messageDismissed. message: %s", message.getMessageId());
    }

    private void logEvent(@NonNull String format, @NonNull Object... args) {
        logger.v(String.format(Locale.ENGLISH, "[CIO-Inbox] sample listener: " + format, args));
    }
}
