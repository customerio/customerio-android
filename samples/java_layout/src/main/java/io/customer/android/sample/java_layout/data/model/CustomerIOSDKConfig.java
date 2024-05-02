package io.customer.android.sample.java_layout.data.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.android.sample.java_layout.utils.StringUtils;

/**
 * Data class to hold SDK configurations. This is only required by sample app for testing purpose.
 */
public class CustomerIOSDKConfig {
    private static class Keys {
        static final String CDP_API_KEY = "cio_sdk_cdp_api_key";
        static final String SITE_ID = "cio_sdk_site_id";
        static final String API_KEY = "cio_sdk_api_key";
        static final String TRACKING_URL = "cio_sdk_tracking_url";
        static final String BQ_SECONDS_DELAY = "cio_sdk_bq_seconds_delay";
        static final String BQ_MIN_TASKS = "cio_sdk_bq_min_tasks";
        static final String TRACK_SCREENS = "cio_sdk_track_screens";
        static final String TRACK_DEVICE_ATTRIBUTES = "cio_sdk_track_device_attributes";
        static final String DEBUG_MODE = "cio_sdk_debug_mode";
    }

    public static CustomerIOSDKConfig getDefaultConfigurations() {
        return new CustomerIOSDKConfig(BuildConfig.CDP_API_KEY,
                BuildConfig.SITE_ID,
                BuildConfig.API_KEY,
                "https://track-sdk.customer.io/",
                30.0,
                10,
                true,
                true,
                true);
    }

    @NonNull
    public static Optional<CustomerIOSDKConfig> fromMap(@NonNull Map<String, String> bundle) {
        String cdpApiKey = bundle.get(Keys.CDP_API_KEY);
        String siteId = bundle.get(Keys.SITE_ID);
        String apiKey = bundle.get(Keys.API_KEY);
        if (TextUtils.isEmpty(cdpApiKey) || TextUtils.isEmpty(siteId) || TextUtils.isEmpty(apiKey)) {
            return Optional.empty();
        }

        CustomerIOSDKConfig defaultConfig = getDefaultConfigurations();
        String trackingURL = bundle.get(Keys.TRACKING_URL);
        Double bqSecondsDelay = StringUtils.parseDouble(bundle.get(Keys.BQ_SECONDS_DELAY), defaultConfig.backgroundQueueSecondsDelay);
        Integer bqMinTasks = StringUtils.parseInteger(bundle.get(Keys.BQ_MIN_TASKS), defaultConfig.backgroundQueueMinNumOfTasks);
        boolean screenTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_SCREENS), defaultConfig.screenTrackingEnabled);
        boolean deviceAttributesTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_DEVICE_ATTRIBUTES), defaultConfig.deviceAttributesTrackingEnabled);
        boolean debugModeEnabled = StringUtils.parseBoolean(bundle.get(Keys.DEBUG_MODE), defaultConfig.debugModeEnabled);

        CustomerIOSDKConfig config = new CustomerIOSDKConfig(cdpApiKey,
                siteId,
                apiKey,
                trackingURL,
                bqSecondsDelay,
                bqMinTasks,
                screenTrackingEnabled,
                deviceAttributesTrackingEnabled,
                debugModeEnabled);
        return Optional.of(config);
    }

    @NonNull
    public static Map<String, String> toMap(@NonNull CustomerIOSDKConfig config) {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(Keys.CDP_API_KEY, config.cdpApiKey);
        bundle.put(Keys.SITE_ID, config.siteId);
        bundle.put(Keys.API_KEY, config.apiKey);
        bundle.put(Keys.TRACKING_URL, config.trackingURL);
        bundle.put(Keys.BQ_SECONDS_DELAY, StringUtils.fromDouble(config.backgroundQueueSecondsDelay));
        bundle.put(Keys.BQ_MIN_TASKS, StringUtils.fromInteger(config.backgroundQueueMinNumOfTasks));
        bundle.put(Keys.TRACK_SCREENS, StringUtils.fromBoolean(config.screenTrackingEnabled));
        bundle.put(Keys.TRACK_DEVICE_ATTRIBUTES, StringUtils.fromBoolean(config.deviceAttributesTrackingEnabled));
        bundle.put(Keys.DEBUG_MODE, StringUtils.fromBoolean(config.debugModeEnabled));
        return bundle;
    }

    @NonNull
    private final String cdpApiKey;
    @NonNull
    private final String siteId;
    @NonNull
    private final String apiKey;
    @Nullable
    private final String trackingURL;
    @Nullable
    private final Double backgroundQueueSecondsDelay;
    @Nullable
    private final Integer backgroundQueueMinNumOfTasks;
    @Nullable
    private final Boolean screenTrackingEnabled;
    @Nullable
    private final Boolean deviceAttributesTrackingEnabled;
    @Nullable
    private final Boolean debugModeEnabled;

    public CustomerIOSDKConfig(@NonNull String cdpApiKey,
                               @NonNull String siteId,
                               @NonNull String apiKey,
                               @Nullable String trackingURL,
                               @Nullable Double backgroundQueueSecondsDelay,
                               @Nullable Integer backgroundQueueMinNumOfTasks,
                               @Nullable Boolean screenTrackingEnabled,
                               @Nullable Boolean deviceAttributesTrackingEnabled,
                               @Nullable Boolean debugModeEnabled) {
        this.cdpApiKey = cdpApiKey;
        this.siteId = siteId;
        this.apiKey = apiKey;
        this.trackingURL = trackingURL;
        this.backgroundQueueSecondsDelay = backgroundQueueSecondsDelay;
        this.backgroundQueueMinNumOfTasks = backgroundQueueMinNumOfTasks;
        this.screenTrackingEnabled = screenTrackingEnabled;
        this.deviceAttributesTrackingEnabled = deviceAttributesTrackingEnabled;
        this.debugModeEnabled = debugModeEnabled;
    }

    @NonNull
    public String getCdpApiKey() {
        return cdpApiKey;
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
    public Double getBackgroundQueueSecondsDelay() {
        return backgroundQueueSecondsDelay;
    }

    @Nullable
    public Integer getBackgroundQueueMinNumOfTasks() {
        return backgroundQueueMinNumOfTasks;
    }


    @Nullable
    public Boolean isScreenTrackingEnabled() {
        return screenTrackingEnabled;
    }

    @Nullable
    public Boolean isDeviceAttributesTrackingEnabled() {
        return deviceAttributesTrackingEnabled;
    }

    @Nullable
    public Boolean isDebugModeEnabled() {
        return debugModeEnabled;
    }

    /**
     * Features by default are nullable to help differentiate between default/null values and
     * values set by user.
     * Unwrapping nullable values here for ease of use by keeping single source of truth for whole
     * sample app.
     */

    public boolean screenTrackingEnabled() {
        return Boolean.FALSE != screenTrackingEnabled;
    }

    public boolean deviceAttributesTrackingEnabled() {
        return Boolean.FALSE != deviceAttributesTrackingEnabled;
    }

    public boolean debugModeEnabled() {
        return Boolean.FALSE != debugModeEnabled;
    }
}
