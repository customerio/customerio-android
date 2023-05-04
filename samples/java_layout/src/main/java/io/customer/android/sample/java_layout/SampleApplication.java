package io.customer.android.sample.java_layout;

import android.app.Application;
import android.text.TextUtils;

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
        Optional<CustomerIOSDKConfig> sdkConfigOptional;
        try {
            Map<String, String> configBundle = appGraph.getPreferencesDataStore().sdkConfig().blockingFirst();
            sdkConfigOptional = CustomerIOSDKConfig.fromMap(configBundle);
        } catch (Exception ignored) {
            sdkConfigOptional = Optional.empty();
        }
        final CustomerIOSDKConfig sdkConfig;
        if (sdkConfigOptional.isPresent()) {
            sdkConfig = sdkConfigOptional.get();
        } else {
            sdkConfig = CustomerIOSDKConfig.getDefaultConfigurations();
        }

        CustomerIO.Builder builder = new CustomerIO.Builder(sdkConfig.getSiteId(), sdkConfig.getApiKey(), this);
        if (sdkConfig.isFeatDebugMode()) {
            builder.setLogLevel(CioLogLevel.DEBUG);
        }

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

        builder.autoTrackScreenViews(sdkConfig.isFeatTrackScreens());
        builder.autoTrackDeviceAttributes(sdkConfig.isFeatTrackDeviceAttributes());

        if (sdkConfig.isFeatInApp()) {
            builder.addCustomerIOModule(new ModuleMessagingInApp());
        }
        builder.addCustomerIOModule(new ModuleMessagingPushFCM());
        builder.build();
    }
}
