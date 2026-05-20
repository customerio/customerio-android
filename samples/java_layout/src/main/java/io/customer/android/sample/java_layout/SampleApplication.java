package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.geofencetest.GeofenceTestNotifier;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
        // Testing-only (geofence-testing branch): local notifications per geofence transition.
        GeofenceTestNotifier.INSTANCE.install(this);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
