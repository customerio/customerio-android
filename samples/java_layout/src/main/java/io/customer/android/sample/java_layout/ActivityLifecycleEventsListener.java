package io.customer.android.sample.java_layout;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.customer.sdk.CustomerIO;
import io.customer.sdk.CustomerIOShared;
import io.customer.sdk.util.Logger;

public class ActivityLifecycleEventsListener implements Application.ActivityLifecycleCallbacks {

    private final String lifecycleEventKey = "ActivityLifecycleEvent";
    private Logger logger = CustomerIOShared.instance().getDiStaticGraph().getLogger();

    public void logEvent(@NonNull String eventName, @NonNull Activity activity) {
        logger.debug(String.format("%s: %s %s", lifecycleEventKey, eventName, activity.getClass().getSimpleName()));

        try {
            CustomerIO sdkInstance = CustomerIO.instance();
            Map<String, String> extras = new HashMap<>();
            extras.put("EventName", eventName);
            extras.put("ActivityName", activity.getClass().getSimpleName());
            sdkInstance.track(lifecycleEventKey, extras);
        } catch (Exception e) {
            logger.debug("SDK not yet initialized");
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) {
        logEvent("onActivityCreated", activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        logEvent("onActivityStarted", activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        logEvent("onActivityResumed", activity);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        logEvent("onActivityPaused", activity);
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        logEvent("onActivityStopped", activity);
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) {
        logEvent("onActivitySaveInstanceState", activity);
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        logEvent("onActivityDestroyed", activity);
    }
}
