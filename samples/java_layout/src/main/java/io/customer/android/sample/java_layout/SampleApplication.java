package io.customer.android.sample.java_layout;

import android.app.Application;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.core.Constants.PreferenceKeys.CustomerIOSDK;
import io.customer.android.sample.java_layout.core.MapUtils;
import io.customer.android.sample.java_layout.di.ApplicationGraph;
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
        Map<String, String> configBundle;
        try {
            configBundle = appGraph.getPreferencesDataStore().sdkConfig().blockingFirst();
        } catch (Exception ignored) {
            configBundle = new HashMap<>();
        }
        final String apiKey = MapUtils.getOrElse(configBundle, CustomerIOSDK.API_KEY, BuildConfig.API_KEY);
        final String siteId = MapUtils.getOrElse(configBundle, CustomerIOSDK.SITE_ID, BuildConfig.SITE_ID);
        final String trackingApiUrl = MapUtils.getOrElse(configBundle, CustomerIOSDK.TRACKING_API_URL, null);
        CustomerIO.Builder builder = new CustomerIO.Builder(siteId, apiKey, this);
        builder.setLogLevel(CioLogLevel.DEBUG);
        if (!TextUtils.isEmpty(trackingApiUrl)) {
            builder.setTrackingApiURL(trackingApiUrl);
        }
        builder.addCustomerIOModule(new ModuleMessagingPushFCM());
        builder.addCustomerIOModule(new ModuleMessagingInApp());
        builder.build();
    }
}
