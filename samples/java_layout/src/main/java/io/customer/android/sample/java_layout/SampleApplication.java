package io.customer.android.sample.java_layout;

import android.app.Application;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.Map;

import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.messaginginapp.ModuleMessagingInApp;
import io.customer.messagingpush.ModuleMessagingPushFCM;
import io.customer.sdk.CustomerIO;
import io.customer.sdk.util.CioLogLevel;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        initCustomerIOSDK();
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }

    private void initCustomerIOSDK() {
        // Get desired SDK config, only required by sample app
        final CustomerIOSDKConfig sdkConfig = getSdkConfig();
        // Initialize Customer.io SDK builder
        CustomerIO.Builder builder = new CustomerIO.Builder(sdkConfig.getSiteId(), sdkConfig.getApiKey(), this);
        // Enable detailed logging for debug builds.
        if (Boolean.TRUE.equals(sdkConfig.isDebugModeEnabled())) {
            builder.setLogLevel(CioLogLevel.DEBUG);
        }
        // Enable optional features of the SDK by adding desired modules.
        // Enables push notification
        builder.addCustomerIOModule(new ModuleMessagingPushFCM());
        // Enables in-app messages
        if (Boolean.TRUE.equals(sdkConfig.isInAppEnabled())) {
            builder.addCustomerIOModule(new ModuleMessagingInApp());
        }
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
    private void configureSdk(CustomerIO.Builder builder, final CustomerIOSDKConfig sdkConfig) {
        final String trackingApiUrl = sdkConfig.getTrackingURL();
        if (!TextUtils.isEmpty(trackingApiUrl)) {
            builder.setTrackingApiURL(trackingApiUrl);
        }

        final Integer bqSecondsDelay = sdkConfig.getBackgroundQueueSecondsDelay();
        if (bqSecondsDelay != null) {
            builder.setBackgroundQueueSecondsDelay(bqSecondsDelay);
        }
        final Integer bqMinTasks = sdkConfig.getBackgroundQueueMinNumOfTasks();
        if (bqMinTasks != null) {
            builder.setBackgroundQueueMinNumberOfTasks(bqMinTasks);
        }

        Boolean screenTrackingEnabled = sdkConfig.isScreenTrackingEnabled();
        if (screenTrackingEnabled != null) {
            builder.autoTrackScreenViews(screenTrackingEnabled);
        }
        Boolean deviceAttributesTrackingEnabled = sdkConfig.isDeviceAttributesTrackingEnabled();
        if (deviceAttributesTrackingEnabled != null) {
            builder.autoTrackDeviceAttributes(deviceAttributesTrackingEnabled);
        }
    }

    /**
     * Retrieves SDK settings, only required by sample app.
     */
    @NonNull
    private CustomerIOSDKConfig getSdkConfig() {
        Optional<CustomerIOSDKConfig> sdkConfig;
        try {
            Map<String, String> configBundle = appGraph.getPreferencesDataStore().sdkConfig().blockingFirst();
            sdkConfig = CustomerIOSDKConfig.fromMap(configBundle);
        } catch (Exception ignored) {
            sdkConfig = Optional.empty();
        }
        if (sdkConfig.isPresent()) {
            return sdkConfig.get();
        } else {
            return CustomerIOSDKConfig.getDefaultConfigurations();
        }
    }
}
