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
        static final String SITE_ID = "site_id";
        static final String API_KEY = "api_key";
        static final String TRACKING_URL = "tracking_url";
        static final String BQ_SECONDS_DELAY = "bq_seconds_delay";
        static final String BQ_MIN_TASKS = "bq_min_tasks";
        static final String FEAT_IN_APP = "feat_in_app";
        static final String FEAT_TRACK_SCREENS = "feat_track_screens";
        static final String FEAT_TRACK_DEVICE_ATTRIBUTES = "feat_track_device_attributes";
        static final String FEAT_DEBUG_MODE = "feat_debug_mode";
    }


    public static CustomerIOSDKConfig getDefaultConfigurations() {
        return new CustomerIOSDKConfig(BuildConfig.SITE_ID,
                BuildConfig.API_KEY,
                null,
                null,
                null,
                false,
                true,
                true,
                true);
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
        boolean featInApp = StringUtils.parseBoolean(bundle.get(Keys.FEAT_IN_APP), defaultConfig.featInApp);
        boolean featTrackScreens = StringUtils.parseBoolean(bundle.get(Keys.FEAT_TRACK_SCREENS), defaultConfig.featTrackScreens);
        boolean featTrackDeviceAttributes = StringUtils.parseBoolean(bundle.get(Keys.FEAT_TRACK_DEVICE_ATTRIBUTES), defaultConfig.featTrackDeviceAttributes);
        boolean featDebugMode = StringUtils.parseBoolean(bundle.get(Keys.FEAT_DEBUG_MODE), defaultConfig.featDebugMode);
        CustomerIOSDKConfig config = new CustomerIOSDKConfig(siteId,
                apiKey,
                trackingURL,
                bqSecondsDelay,
                bqMinTasks,
                featInApp,
                featTrackScreens,
                featTrackDeviceAttributes,
                featDebugMode);
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
    private final boolean featInApp;
    private final boolean featTrackScreens;
    private final boolean featTrackDeviceAttributes;
    private final boolean featDebugMode;

    public CustomerIOSDKConfig(@NonNull String siteId,
                               @NonNull String apiKey,
                               @Nullable String trackingURL,
                               @Nullable Integer backgroundQueueSecondsDelay,
                               @Nullable Integer backgroundQueueMinNumOfTasks,
                               boolean featInApp,
                               boolean featTrackScreens,
                               boolean featTrackDeviceAttributes,
                               boolean featDebugMode) {
        this.siteId = siteId;
        this.apiKey = apiKey;
        this.trackingURL = trackingURL;
        this.backgroundQueueSecondsDelay = backgroundQueueSecondsDelay;
        this.backgroundQueueMinNumOfTasks = backgroundQueueMinNumOfTasks;
        this.featInApp = featInApp;
        this.featTrackScreens = featTrackScreens;
        this.featTrackDeviceAttributes = featTrackDeviceAttributes;
        this.featDebugMode = featDebugMode;
    }

    public Map<String, String> toMap() {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(Keys.SITE_ID, siteId);
        bundle.put(Keys.API_KEY, apiKey);
        bundle.put(Keys.TRACKING_URL, trackingURL);
        bundle.put(Keys.BQ_SECONDS_DELAY, StringUtils.fromInteger(backgroundQueueSecondsDelay));
        bundle.put(Keys.BQ_MIN_TASKS, StringUtils.fromInteger(backgroundQueueMinNumOfTasks));
        bundle.put(Keys.FEAT_IN_APP, StringUtils.fromBoolean(featInApp));
        bundle.put(Keys.FEAT_TRACK_SCREENS, StringUtils.fromBoolean(featTrackScreens));
        bundle.put(Keys.FEAT_TRACK_DEVICE_ATTRIBUTES, StringUtils.fromBoolean(featTrackDeviceAttributes));
        bundle.put(Keys.FEAT_DEBUG_MODE, StringUtils.fromBoolean(featDebugMode));
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

    public boolean isFeatInApp() {
        return featInApp;
    }

    public boolean isFeatTrackScreens() {
        return featTrackScreens;
    }

    public boolean isFeatTrackDeviceAttributes() {
        return featTrackDeviceAttributes;
    }

    public boolean isFeatDebugMode() {
        return featDebugMode;
    }
}
