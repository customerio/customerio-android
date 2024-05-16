package io.customer.android.sample.java_layout.sdk;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.Map;

import io.customer.android.sample.java_layout.BuildConfig;
import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.messaginginapp.MessagingInAppModuleConfig;
import io.customer.messaginginapp.ModuleMessagingInApp;
import io.customer.messagingpush.ModuleMessagingPushFCM;
import io.customer.sdk.CustomerIO;
import io.customer.sdk.CustomerIOBuilder;
import io.customer.sdk.core.util.CioLogLevel;

/**
 * Repository class to hold all Customer.io related operations at single place
 */
public class CustomerIORepository {
    public void initializeSdk(SampleApplication application) {
        ApplicationGraph appGraph = application.getApplicationGraph();
        // Get desired SDK config, only required by sample app
        final CustomerIOSDKConfig sdkConfig = getSdkConfig(appGraph.getPreferencesDataStore());

        // TODO: Remove old builder and use new builder only to initialize the SDK
        // The new method should be called after the old method till the old method is removed
        // This is because the push and in-app modules are still using properties only initialized in the old method
        CustomerIO.Builder oldBuilder = new CustomerIO.Builder(sdkConfig.getSiteId(), BuildConfig.API_KEY, application);
        oldBuilder.build();

        // Initialize Customer.io SDK builder
        CustomerIOBuilder builder = new CustomerIOBuilder(application, sdkConfig.getCdpApiKey());

        // Enable detailed logging for debug builds.
        if (sdkConfig.debugModeEnabled()) {
            builder.setLogLevel(CioLogLevel.DEBUG);
        }

        // Enable optional features of the SDK by adding desired modules.
        // Enables push notification
        builder.addCustomerIOModule(new ModuleMessagingPushFCM());
        // Enables in-app messages
        builder.addCustomerIOModule(new ModuleMessagingInApp(
                new MessagingInAppModuleConfig.Builder()
                        .setEventListener(new InAppMessageEventListener(appGraph.getLogger()))
                        .build()
        ));

        // Modify SDK settings for testing purposes only.
        // If you don't need to override any of these settings, you can skip this line.
        configureSdk(builder, sdkConfig);

        // Finally, build to finish initializing the SDK.
        builder.build();
    }

    /**
     * Makes modifications to SDK configurations. It is mainly for testing
     * purposes and may not be needed unless there is a need to override any
     * default configuration from the SDK.
     */
    private void configureSdk(CustomerIOBuilder builder, final CustomerIOSDKConfig sdkConfig) {
        final String apiHost = sdkConfig.getApiHost();
        if (!TextUtils.isEmpty(apiHost)) {
            builder.setApiHost(apiHost);
        }

        final String cdnHost = sdkConfig.getCdnHost();
        if (!TextUtils.isEmpty(cdnHost)) {
            builder.setCdnHost(cdnHost);
        }

        builder.setMigrationSiteId(sdkConfig.getSiteId());
        if (sdkConfig.getFlushAt() != null) {
            builder.setFlushAt(sdkConfig.getFlushAt());
        }
        if (sdkConfig.getFlushInterval() != null) {
            builder.setFlushInterval(sdkConfig.getFlushInterval());
        }

        final Boolean screenTrackingEnabled = sdkConfig.isScreenTrackingEnabled();
        if (screenTrackingEnabled != null) {
            builder.setAutoTrackActivityScreens(screenTrackingEnabled);
        }
        final Boolean deviceAttributesTrackingEnabled = sdkConfig.isDeviceAttributesTrackingEnabled();
        if (deviceAttributesTrackingEnabled != null) {
            builder.setAutoTrackDeviceAttributes(deviceAttributesTrackingEnabled);
        }
    }

    public void identify(@NonNull String email, @NonNull Map<String, String> attributes) {
        io.customer.sdk.android.CustomerIO.instance().identify(email, attributes);
    }

    public void clearIdentify() {
        io.customer.sdk.android.CustomerIO.instance().clearIdentify();
    }

    public void trackEvent(@NonNull String eventName, @NonNull Map<String, String> extras) {
        io.customer.sdk.android.CustomerIO.instance().track(eventName, extras);
    }

    public void setDeviceAttributes(@NonNull Map<String, String> attributes) {
        CustomerIO.instance().setDeviceAttributes(attributes);
    }

    public void setProfileAttributes(@NonNull Map<String, String> attributes) {
        io.customer.sdk.android.CustomerIO.instance().setProfileAttributes(attributes);
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
