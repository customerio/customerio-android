package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.sdk.CustomerIOSDK;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        CustomerIOSDK.initializeSDK(SampleApplication.this);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
