package io.customer.android.sample.java_layout;

import android.app.Application;
import android.os.StrictMode;

import io.customer.android.sample.java_layout.di.ApplicationGraph;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        // Enable StrictMode to catch accidental disk or network access on main thread
        // Only enable in debug builds to avoid performance impact in release builds
        if (BuildConfig.DEBUG) {
            enableStrictMode();
        }
        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }

    private void enableStrictMode() {
        // Thread policy - detects network calls, disk reads, custom slow calls on main thread
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectNetwork()   // Detect network calls on main thread
                .detectDiskReads() // Detect disk reads on main thread
                .detectDiskWrites() // Detect disk writes on main thread
                .detectCustomSlowCalls() // Detect custom slow calls marked with StrictMode.noteSlowCall()
                .penaltyLog()      // Log violations to LogCat
                .penaltyDialog()   // Show dialog for violations (useful for debugging)
                .build());

        // VM policy - detects leaks and other VM-level violations
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects() // Detect leaked SQLite objects
                .detectLeakedClosableObjects() // Detect leaked closeable objects
                .detectActivityLeaks()         // Detect leaked activities
                .penaltyLog()                  // Log violations to LogCat
                .build());
    }
}
