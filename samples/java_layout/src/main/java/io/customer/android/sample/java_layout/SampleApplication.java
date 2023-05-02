package io.customer.android.sample.java_layout;

import android.app.Application;

import io.customer.android.sample.java_layout.di.ApplicationGraph;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }
}
