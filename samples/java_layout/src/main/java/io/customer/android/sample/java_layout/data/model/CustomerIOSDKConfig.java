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
import io.customer.sdk.core.util.CioLogLevel;
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
        static final String TRACK_SCREENS = "cio_sdk_track_screens";
        static final String TRACK_DEVICE_ATTRIBUTES = "cio_sdk_track_device_attributes";
        static final String LOG_LEVEL = "cio_sdk_log_level";
        static final String REGION = "cio_sdk_region";
        static final String TRACK_APPLICATION_LIFECYCLE = "cio_sdk_track_application_lifecycle";
        static final String TEST_MODE_ENABLED = "cio_sdk_test_mode";
        static final String IN_APP_MESSAGING_ENABLED = "cio_sdk_in_app_messaging_enabled";
    }

    public static CustomerIOSDKConfig getDefaultConfigurations() {
        return new CustomerIOSDKConfig(BuildConfig.CDP_API_KEY,
                BuildConfig.SITE_ID,
                RegionExtKt.apiHost(Region.US.INSTANCE),
                RegionExtKt.cdnHost(Region.US.INSTANCE),
                true,
                true,
                CioLogLevel.DEBUG,
                Region.US.INSTANCE,
                true,
                false,
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
        boolean screenTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_SCREENS), defaultConfig.screenTrackingEnabled);
        boolean deviceAttributesTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_DEVICE_ATTRIBUTES), defaultConfig.deviceAttributesTrackingEnabled);
        CioLogLevel logLevel = CioLogLevel.Companion.getLogLevel(bundle.get(Keys.LOG_LEVEL), CioLogLevel.DEBUG);
        Region region = Region.Companion.getRegion(bundle.get(Keys.REGION), Region.US.INSTANCE);
        boolean applicationLifecycleTrackingEnabled = StringUtils.parseBoolean(bundle.get(Keys.TRACK_APPLICATION_LIFECYCLE), defaultConfig.applicationLifecycleTrackingEnabled);
        boolean testModeEnabled = StringUtils.parseBoolean(bundle.get(Keys.TEST_MODE_ENABLED), defaultConfig.testModeEnabled);
        boolean inAppMessagingEnabled = StringUtils.parseBoolean(bundle.get(Keys.IN_APP_MESSAGING_ENABLED), defaultConfig.inAppMessagingEnabled);

        CustomerIOSDKConfig config = new CustomerIOSDKConfig(cdpApiKey,
                siteId,
                apiHost,
                cdnHost,
                screenTrackingEnabled,
                deviceAttributesTrackingEnabled,
                logLevel,
                region,
                applicationLifecycleTrackingEnabled,
                testModeEnabled,
                inAppMessagingEnabled);
        return Optional.of(config);
    }

    @NonNull
    public static Map<String, String> toMap(@NonNull CustomerIOSDKConfig config) {
        Map<String, String> bundle = new HashMap<>();
        bundle.put(Keys.CDP_API_KEY, config.cdpApiKey);
        bundle.put(Keys.SITE_ID, config.siteId);
        bundle.put(Keys.API_HOST, config.apiHost);
        bundle.put(Keys.CDN_HOST, config.cdnHost);
        bundle.put(Keys.TRACK_SCREENS, StringUtils.fromBoolean(config.screenTrackingEnabled));
        bundle.put(Keys.TRACK_DEVICE_ATTRIBUTES, StringUtils.fromBoolean(config.deviceAttributesTrackingEnabled));
        bundle.put(Keys.LOG_LEVEL, config.logLevel.name());
        bundle.put(Keys.REGION, config.getRegion().getCode());
        bundle.put(Keys.TRACK_APPLICATION_LIFECYCLE, StringUtils.fromBoolean(config.applicationLifecycleTrackingEnabled));
        bundle.put(Keys.TEST_MODE_ENABLED, StringUtils.fromBoolean(config.testModeEnabled));
        bundle.put(Keys.IN_APP_MESSAGING_ENABLED, StringUtils.fromBoolean(config.inAppMessagingEnabled));
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
    private final boolean screenTrackingEnabled;
    private final boolean deviceAttributesTrackingEnabled;
    @NonNull
    private final CioLogLevel logLevel;
    @NonNull
    private final Region region;
    private final boolean applicationLifecycleTrackingEnabled;
    private final boolean testModeEnabled;
    private final boolean inAppMessagingEnabled;

    public CustomerIOSDKConfig(@NonNull String cdpApiKey,
                               @NonNull String siteId,
                               @Nullable String apiHost,
                               @Nullable String cdnHost,
                               boolean screenTrackingEnabled,
                               boolean deviceAttributesTrackingEnabled,
                               @NonNull CioLogLevel logLevel,
                               @NonNull Region region,
                               boolean applicationLifecycleTrackingEnabled,
                               boolean testModeEnabled,
                               boolean inAppMessagingEnabled) {
        this.cdpApiKey = cdpApiKey;
        this.siteId = siteId;
        this.apiHost = apiHost;
        this.cdnHost = cdnHost;
        this.screenTrackingEnabled = screenTrackingEnabled;
        this.deviceAttributesTrackingEnabled = deviceAttributesTrackingEnabled;
        this.logLevel = logLevel;
        this.region = region;
        this.applicationLifecycleTrackingEnabled = applicationLifecycleTrackingEnabled;
        this.testModeEnabled = testModeEnabled;
        this.inAppMessagingEnabled = inAppMessagingEnabled;
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

    public boolean isScreenTrackingEnabled() {
        return screenTrackingEnabled;
    }

    public boolean isDeviceAttributesTrackingEnabled() {
        return deviceAttributesTrackingEnabled;
    }

    @NonNull
    public CioLogLevel getLogLevel() {
        return logLevel;
    }

    public boolean isTestModeEnabled() {
        return testModeEnabled;
    }

    public boolean isInAppMessagingEnabled() {
        return inAppMessagingEnabled;
    }

    public boolean isApplicationLifecycleTrackingEnabled() {
        return applicationLifecycleTrackingEnabled;
    }

    @NonNull
    public Region getRegion() {
        return region;
    }
}
