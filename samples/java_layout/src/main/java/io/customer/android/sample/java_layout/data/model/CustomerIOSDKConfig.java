package io.customer.android.sample.java_layout.data.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.core.StringUtils;
import io.customer.android.sample.java_layout.support.Optional;

public class CustomerIOSDKConfig {
    private static class Keys {
        static final String SITE_ID = "cio_sdk_site_id";
        static final String API_KEY = "cio_sdk_api_key";
        static final String TRACKING_URL = "cio_sdk_tracking_url";
        static final String BQ_SECONDS_DELAY = "cio_sdk_bq_seconds_delay";
        static final String BQ_MIN_TASKS = "cio_sdk_bq_min_tasks";
        static final String ENABLE_IN_APP = "cio_sdk_enable_in_app";
        static final String TRACK_SCREENS = "cio_sdk_track_screens";
        static final String TRACK_DEVICE_ATTRIBUTES = "cio_sdk_track_device_attributes";
        static final String DEBUG_MODE = "cio_sdk_debug_mode";
    }


    public static CustomerIOSDKConfig getDefaultConfigurations() {
        return new CustomerIOSDKConfig(BuildConfig.SITE_ID,
                BuildConfig.API_KEY,
                BuildConfig.TRACKING_URL,
                BuildConfig.BQ_SECONDS_DELAY,
                BuildConfig.BQ_MIN_TASKS,
                BuildConfig.ENABLE_IN_APP,
                BuildConfig.TRACK_SCREENS,
                BuildConfig.TRACK_DEVICE_ATTRIBUTES,
                BuildConfig.DEBUG_MODE);
    }

    @NonNull
    public static Optional<CustomerIOSDKConfig> fromMap(Map<String, String> bundle) {
        String siteId = bundle.get(Keys.SITE_ID);
        String apiKey = bundle.get(Keys.API_KEY);
        if (TextUtils.isEmpty(siteId) || TextUtils.isEmpty(apiKey)) {
            return Optional.empty();
        }

        CustomerIOSDKConfig defaultConfig = getDefaultConfigurations();
        String trackingURL = bundle.get(Keys.TRACKING_URL);
        Integer bqSecondsDelay = StringUtils.parseInteger(bundle.get(Keys.BQ_SECONDS_DELAY), defaultConfig.backgroundQueueSecondsDelay);
        Integer bqMinTasks = StringUtils.parseInteger(bundle.get(Keys.BQ_MIN_TASKS), defaultConfig.backgroundQueueMinNumOfTasks);
        boolean inAppEnabled = StringUtils.parseBoolean(bundle.get(Keys.ENABLE_IN_APP), defaultConfig.inAppEnabled);
        boolean screenTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_SCREENS), defaultConfig.screenTrackingEnabled);
        boolean deviceAttributesTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_DEVICE_ATTRIBUTES), defaultConfig.deviceAttributesTrackingEnabled);
        boolean debugModeEnabled = StringUtils.parseBoolean(bundle.get(Keys.DEBUG_MODE), defaultConfig.debugModeEnabled);

        CustomerIOSDKConfig config = new CustomerIOSDKConfig(siteId,
                apiKey,
                trackingURL,
                bqSecondsDelay,
                bqMinTasks,
                inAppEnabled,
                screenTrackingEnabled,
                deviceAttributesTrackingEnabled,
                debugModeEnabled);
        return Optional.of(config);
    }

    @NonNull
    private final String siteId;
    @NonNull
    private final String apiKey;
    @Nullable
    private final String trackingURL;
    @Nullable
    private final Integer backgroundQueueSecondsDelay;
    @Nullable
    private final Integer backgroundQueueMinNumOfTasks;
    private final boolean inAppEnabled;
    private final boolean screenTrackingEnabled;
    private final boolean deviceAttributesTrackingEnabled;
    private final boolean debugModeEnabled;

    public CustomerIOSDKConfig(@NonNull String siteId,
                               @NonNull String apiKey,
                               @Nullable String trackingURL,
                               @Nullable Integer backgroundQueueSecondsDelay,
                               @Nullable Integer backgroundQueueMinNumOfTasks,
                               boolean inAppEnabled,
                               boolean screenTrackingEnabled,
                               boolean deviceAttributesTrackingEnabled,
                               boolean debugModeEnabled) {
        this.siteId = siteId;
        this.apiKey = apiKey;
        this.trackingURL = trackingURL;
        this.backgroundQueueSecondsDelay = backgroundQueueSecondsDelay;
        this.backgroundQueueMinNumOfTasks = backgroundQueueMinNumOfTasks;
        this.inAppEnabled = inAppEnabled;
        this.screenTrackingEnabled = screenTrackingEnabled;
        this.deviceAttributesTrackingEnabled = deviceAttributesTrackingEnabled;
        this.debugModeEnabled = debugModeEnabled;
    }

    public Map<String, String> toMap() {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(Keys.SITE_ID, siteId);
        bundle.put(Keys.API_KEY, apiKey);
        bundle.put(Keys.TRACKING_URL, trackingURL);
        bundle.put(Keys.BQ_SECONDS_DELAY, StringUtils.fromInteger(backgroundQueueSecondsDelay));
        bundle.put(Keys.BQ_MIN_TASKS, StringUtils.fromInteger(backgroundQueueMinNumOfTasks));
        bundle.put(Keys.ENABLE_IN_APP, StringUtils.fromBoolean(inAppEnabled));
        bundle.put(Keys.TRACK_SCREENS, StringUtils.fromBoolean(screenTrackingEnabled));
        bundle.put(Keys.TRACK_DEVICE_ATTRIBUTES, StringUtils.fromBoolean(deviceAttributesTrackingEnabled));
        bundle.put(Keys.DEBUG_MODE, StringUtils.fromBoolean(debugModeEnabled));
        return bundle;
    }

    @NonNull
    public String getSiteId() {
        return siteId;
    }

    @NonNull
    public String getApiKey() {
        return apiKey;
    }

    @Nullable
    public String getTrackingURL() {
        return trackingURL;
    }

    @Nullable
    public Integer getBackgroundQueueSecondsDelay() {
        return backgroundQueueSecondsDelay;
    }

    @Nullable
    public Integer getBackgroundQueueMinNumOfTasks() {
        return backgroundQueueMinNumOfTasks;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }

    public boolean isScreenTrackingEnabled() {
        return screenTrackingEnabled;
    }

    public boolean isDeviceAttributesTrackingEnabled() {
        return deviceAttributesTrackingEnabled;
    }

    public boolean isDebugModeEnabled() {
        return debugModeEnabled;
    }
}
