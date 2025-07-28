package io.customer.android.sample.java_layout;

import android.os.Bundle;
import android.os.StrictMode;
import androidx.test.runner.AndroidJUnitRunner;

public class StrictModeTestRunner extends AndroidJUnitRunner {

    @Override
    public void onCreate(Bundle arguments) {
        // First, let the AndroidJUnitRunner do its thing.
        // Else it will throw the StrictModeViolation here
        super.onCreate(arguments);

        // Now, set up our StrictMode policies.
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
                .build());

        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
                .build());
    }
}
