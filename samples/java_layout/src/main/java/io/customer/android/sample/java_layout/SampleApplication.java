package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.geofencetest.GeofenceTestNotifier;
import io.customer.android.sample.java_layout.sdklogger.SdkFileLogger;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        // Testing-only (geofence-testing branch): mirror SDK logs to a per-session file.
        // Installed before SDK init so initialization logs are captured too.
        new SdkFileLogger(this).install();
        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
        // Testing-only (geofence-testing branch): local notifications per geofence transition.
        GeofenceTestNotifier.INSTANCE.install(this);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
