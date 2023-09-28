package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);
    public ActivityLifecycleEventsListener lifecycleEventsListener;

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
        lifecycleEventsListener = new ActivityLifecycleEventsListener();
        registerActivityLifecycleCallbacks(lifecycleEventsListener);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
