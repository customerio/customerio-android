package io.customer.android.sample.java_layout.data.model;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.android.sample.java_layout.utils.StringUtils;
import io.customer.datapipelines.extensions.RegionExtKt;
import io.customer.sdk.data.model.Region;

/**
 * Data class to hold SDK configurations. This is only required by sample app for testing purpose.
 */
public class CustomerIOSDKConfig {
    private static class Keys {
        static final String CDP_API_KEY = "cio_sdk_cdp_api_key";
        static final String SITE_ID = "cio_sdk_site_id";
        static final String API_HOST = "cio_sdk_api_host";
        static final String CDN_HOST = "cio_sdk_cdn_host";
        static final String FLUSH_INTERVAL = "cio_sdk_flush_interval";
        static final String FLUSH_AT = "cio_sdk_flush_at";
        static final String TRACK_SCREENS = "cio_sdk_track_screens";
        static final String TRACK_DEVICE_ATTRIBUTES = "cio_sdk_track_device_attributes";
        static final String DEBUG_MODE = "cio_sdk_debug_mode";
    }

    public static CustomerIOSDKConfig getDefaultConfigurations() {
        return new CustomerIOSDKConfig(BuildConfig.CDP_API_KEY,
                BuildConfig.SITE_ID,
                RegionExtKt.apiHost(Region.US.INSTANCE),
                RegionExtKt.cdnHost(Region.US.INSTANCE),
                30,
                20,
                true,
                true,
                true);
    }

    @NonNull
    public static Optional<CustomerIOSDKConfig> fromMap(@NonNull Map<String, String> bundle) {
        String cdpApiKey = bundle.get(Keys.CDP_API_KEY);
        String siteId = bundle.get(Keys.SITE_ID);
        if (TextUtils.isEmpty(cdpApiKey) || TextUtils.isEmpty(siteId)) {
            return Optional.empty();
        }

        CustomerIOSDKConfig defaultConfig = getDefaultConfigurations();
        String apiHost = bundle.get(Keys.API_HOST);
        String cdnHost = bundle.get(Keys.CDN_HOST);
        Integer flushInterval = StringUtils.parseInteger(bundle.get(Keys.FLUSH_INTERVAL), defaultConfig.flushInterval);
        Integer flushAt = StringUtils.parseInteger(bundle.get(Keys.FLUSH_AT), defaultConfig.flushAt);
        boolean screenTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_SCREENS), defaultConfig.screenTrackingEnabled);
        boolean deviceAttributesTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_DEVICE_ATTRIBUTES), defaultConfig.deviceAttributesTrackingEnabled);
        boolean debugModeEnabled = StringUtils.parseBoolean(bundle.get(Keys.DEBUG_MODE), defaultConfig.debugModeEnabled);

        CustomerIOSDKConfig config = new CustomerIOSDKConfig(cdpApiKey,
                siteId,
                apiHost,
                cdnHost,
                flushInterval,
                flushAt,
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
        bundle.put(Keys.API_HOST, config.apiHost);
        bundle.put(Keys.CDN_HOST, config.cdnHost);
        bundle.put(Keys.FLUSH_INTERVAL, StringUtils.fromInteger(config.flushInterval));
        bundle.put(Keys.FLUSH_AT, StringUtils.fromInteger(config.flushAt));
        bundle.put(Keys.TRACK_SCREENS, StringUtils.fromBoolean(config.screenTrackingEnabled));
        bundle.put(Keys.TRACK_DEVICE_ATTRIBUTES, StringUtils.fromBoolean(config.deviceAttributesTrackingEnabled));
        bundle.put(Keys.DEBUG_MODE, StringUtils.fromBoolean(config.debugModeEnabled));
        return bundle;
    }

    @NonNull
    private final String cdpApiKey;
    @NonNull
    private final String siteId;
    @Nullable
    private final String apiHost;
    @Nullable
    private final String cdnHost;
    @Nullable
    private final Integer flushInterval;
    @Nullable
    private final Integer flushAt;
    @Nullable
    private final Boolean screenTrackingEnabled;
    @Nullable
    private final Boolean deviceAttributesTrackingEnabled;
    @Nullable
    private final Boolean debugModeEnabled;

    public CustomerIOSDKConfig(@NonNull String cdpApiKey,
                               @NonNull String siteId,
                               @Nullable String apiHost,
                               @Nullable String cdnHost,
                               @Nullable Integer flushInterval,
                               @Nullable Integer flushAt,
                               @Nullable Boolean screenTrackingEnabled,
                               @Nullable Boolean deviceAttributesTrackingEnabled,
                               @Nullable Boolean debugModeEnabled) {
        this.cdpApiKey = cdpApiKey;
        this.siteId = siteId;
        this.apiHost = apiHost;
        this.cdnHost = cdnHost;
        this.flushInterval = flushInterval;
        this.flushAt = flushAt;
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

    @Nullable
    public String getApiHost() {
        return apiHost;
    }

    @Nullable
    public String getCdnHost() {
        return cdnHost;
    }

    @Nullable
    public Integer getFlushInterval() {
        return flushInterval;
    }

    @Nullable
    public Integer getFlushAt() {
        return flushAt;
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
