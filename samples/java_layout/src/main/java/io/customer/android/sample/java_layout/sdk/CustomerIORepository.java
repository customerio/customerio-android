package io.customer.android.sample.java_layout.sdk;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.Map;

import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.messaginginapp.MessagingInAppModuleConfig;
import io.customer.messaginginapp.ModuleMessagingInApp;
import io.customer.location.LocationModuleConfig;
import io.customer.location.LocationTrackingMode;
import io.customer.location.ModuleLocation;
import android.graphics.Color;

import io.customer.messagingpush.MessagingPushModuleConfig;
import io.customer.messagingpush.ModuleMessagingPushFCM;
import io.customer.messagingpush.livenotification.LiveNotificationBranding;
import io.customer.messagingpush.livenotification.LiveNotificationType;
import io.customer.sdk.CustomerIO;
import io.customer.sdk.CustomerIOConfig;
import io.customer.sdk.CustomerIOConfigBuilder;

/**
 * Repository class to hold all Customer.io related operations at single place
 */
public class CustomerIORepository {

    /**
     * The push module instance, retained so the live-notification demo screen can call
     * {@link ModuleMessagingPushFCM#startLiveNotification} (the public local-start API).
     */
    public static ModuleMessagingPushFCM messagingPushModule;

    public void initializeSdk(SampleApplication application) {
        ApplicationGraph appGraph = application.getApplicationGraph();
        // Get desired SDK config, only required by sample app
        final CustomerIOSDKConfig sdkConfig = getSdkConfig(appGraph.getPreferencesDataStore());

        // Initialize Customer.io SDK builder
        CustomerIOConfigBuilder builder = new CustomerIOConfigBuilder(application, sdkConfig.getCdpApiKey());

        // Modify SDK settings for testing purposes only.
        // If you don't need to override any of these settings, you can skip this line.
        configureSdk(builder, sdkConfig);

        // Enable optional features of the SDK by adding desired modules.
        // Enables push notification with live-notification branding registered once at init.
        messagingPushModule = new ModuleMessagingPushFCM(
                new MessagingPushModuleConfig.Builder()
                        .setLiveNotificationBranding(new LiveNotificationBranding(
                                "Customer.io Sample",
                                Color.parseColor("#1B5E20"),
                                null
                        ))
                        // App-rendered custom types go through this callback.
                        .setNotificationCallback(new LiveNotificationCallback())
                        // Live notifications are opt-in: enable the built-in template
                        // types plus our two custom (app-rendered) types.
                        .enableLiveNotificationTypes(
                                LiveNotificationType.DELIVERY_TRACKING,
                                LiveNotificationType.FLIGHT_STATUS,
                                LiveNotificationType.LIVE_SCORE,
                                LiveNotificationType.COUNTDOWN_TIMER,
                                LiveNotificationType.AUCTION_BID,
                                LiveNotificationCallback.ACTIVITY_TYPE_RIDESHARE,
                                LiveNotificationCallback.ACTIVITY_TYPE_WORKOUT
                        )
                        .build()
        );
        builder.addCustomerIOModule(messagingPushModule);

        // Enables location tracking
        builder.addCustomerIOModule(new ModuleLocation(
                new LocationModuleConfig.Builder()
                        .setLocationTrackingMode(LocationTrackingMode.MANUAL)
                        .build()
        ));

        // Enables in-app messages
        if (sdkConfig.isInAppMessagingEnabled()) {
            builder.addCustomerIOModule(new ModuleMessagingInApp(
                    new MessagingInAppModuleConfig.Builder(sdkConfig.getSiteId(), sdkConfig.getRegion())
                            .setEventListener(new InAppMessageEventListener(appGraph.getLogger()))
                            .build()
            ));
        }
        CustomerIO.initialize(builder.build());
    }

    /**
     * Makes modifications to SDK configurations. It is mainly for testing
     * purposes and may not be needed unless there is a need to override any
     * default configuration from the SDK.
     */
    private void configureSdk(CustomerIOConfigBuilder builder, final CustomerIOSDKConfig sdkConfig) {
        builder.migrationSiteId(sdkConfig.getSiteId());

        final String apiHost = sdkConfig.getApiHost();
        if (!TextUtils.isEmpty(apiHost)) {
            builder.apiHost(apiHost);
        }

        final String cdnHost = sdkConfig.getCdnHost();
        if (!TextUtils.isEmpty(cdnHost)) {
            builder.cdnHost(cdnHost);
        }

        if (sdkConfig.isTestModeEnabled()) {
            builder.flushAt(1);
        }

        builder.autoTrackActivityScreens(sdkConfig.isScreenTrackingEnabled());
        builder.autoTrackDeviceAttributes(sdkConfig.isDeviceAttributesTrackingEnabled());
        builder.trackApplicationLifecycleEvents(sdkConfig.isApplicationLifecycleTrackingEnabled());
        builder.region(sdkConfig.getRegion());
        builder.logLevel(sdkConfig.getLogLevel());
        builder.screenViewUse(sdkConfig.getScreenViewUse());
    }

    public void identify(@NonNull String email, @NonNull Map<String, String> attributes) {
        CustomerIO.instance().identify(email, attributes);
    }

    public void clearIdentify() {
        CustomerIO.instance().clearIdentify();
    }

    public void trackEvent(@NonNull String eventName, @NonNull Map<String, String> extras) {
        CustomerIO.instance().track(eventName, extras);
    }

    public void setDeviceAttributes(@NonNull Map<String, String> attributes) {
        CustomerIO.instance().setDeviceAttributes(attributes);
    }

    public void setProfileAttributes(@NonNull Map<String, String> attributes) {
        CustomerIO.instance().setProfileAttributes(attributes);
    }

    /*
     *******************************************************
     * Code below this line is required by sample app only *
     *******************************************************
     */

    // Retrieves SDK settings from data store
    @NonNull
    private CustomerIOSDKConfig getSdkConfig(PreferencesDataStore dataStore) {
        try {
            Map<String, String> configBundle = dataStore.sdkConfig().blockingFirst();
            Optional<CustomerIOSDKConfig> sdkConfig = CustomerIOSDKConfig.fromMap(configBundle);
            if (sdkConfig.isPresent()) {
                return sdkConfig.get();
            }
        } catch (Exception ignored) {
            // Ignore exception if no configurations are available in data store
        }

        // Return default configurations if no configurations were saved in data store
        return CustomerIOSDKConfig.getDefaultConfigurations();
    }
}
