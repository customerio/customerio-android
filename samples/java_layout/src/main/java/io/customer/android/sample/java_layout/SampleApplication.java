package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.diagnostics.DiagnosticLog;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();

        // Field-drive diagnostics sink, installed before anything else runs.
        //
        // A cold background wake for a geofence crossing — the case that matters most — runs
        // Application.onCreate first and reaches SDK code within milliseconds. Anything installed
        // after SDK initialization misses the wake it was meant to observe.
        DiagnosticLog.start(SampleApplication.this);

        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
