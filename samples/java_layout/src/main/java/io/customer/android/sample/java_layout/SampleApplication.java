package io.customer.android.sample.java_layout;

import android.app.Application;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.sdk.CustomerIO;

public class SampleApplication extends Application {
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    /**
     * Pre-init buffer validation scenarios. Set this to one of the cases below,
     * run the app, and watch logcat for "PreInitEventBuffer" entries. Server
     * arrival is visible in the workspace's Activity Log using the configured
     * cdpApiKey. Default is OFF, leaving normal app behavior unchanged.
     */
    enum PreInitBufferScenario {
        OFF,
        S1_SINGLE_EVENT,
        S2_ORDERING_ACROSS_IDENTITIES,
        S3_OVERFLOW,
        S4_ZERO_EVENTS,
        S5_SAFE_DEFAULTS,
        S7_DOUBLE_INIT,
    }

    private static final PreInitBufferScenario PRE_INIT_SCENARIO = PreInitBufferScenario.OFF;
    private static final String PRE_INIT_LOG_TAG = "PreInitBufferTest";

    @Override
    public void onCreate() {
        super.onCreate();
        runPreInitScenario(PRE_INIT_SCENARIO);

        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);

        if (PRE_INIT_SCENARIO == PreInitBufferScenario.S7_DOUBLE_INIT) {
            // Verify second initialize() is a no-op (no double drain).
            appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);
        }
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }

    private void runPreInitScenario(PreInitBufferScenario scenario) {
        CustomerIO sdk = CustomerIO.instance();
        switch (scenario) {
            case OFF:
                return;
            case S1_SINGLE_EVENT: {
                Map<String, String> props = new HashMap<>();
                props.put("case", "S1");
                sdk.track("preinit_single", props);
                return;
            }
            case S2_ORDERING_ACROSS_IDENTITIES: {
                Map<String, String> aliceTraits = new HashMap<>();
                aliceTraits.put("case", "S2");
                sdk.identify("alice", aliceTraits);

                Map<String, String> eventA = new HashMap<>();
                eventA.put("case", "S2");
                sdk.track("eventA", eventA);

                sdk.clearIdentify();

                Map<String, String> bobTraits = new HashMap<>();
                bobTraits.put("case", "S2");
                sdk.identify("bob", bobTraits);

                Map<String, String> eventB = new HashMap<>();
                eventB.put("case", "S2");
                sdk.track("eventB", eventB);
                return;
            }
            case S3_OVERFLOW: {
                for (int i = 0; i < 105; i++) {
                    Map<String, String> overflowProps = new HashMap<>();
                    overflowProps.put("index", Integer.toString(i));
                    sdk.track("preinit_overflow", overflowProps);
                }
                return;
            }
            case S4_ZERO_EVENTS:
            case S7_DOUBLE_INIT:
                // No pre-init work; validation is in the post-init log output.
                return;
            case S5_SAFE_DEFAULTS: {
                // Read-side accessor must not crash before CustomerIO.initialize() runs.
                String token = sdk.getRegisteredDeviceToken();
                Log.i(PRE_INIT_LOG_TAG, "S5 pre-init registeredDeviceToken: " + (token != null ? token : "null"));
                return;
            }
        }
    }
}
