package io.customer.android.sample.java_layout;

import android.app.Application;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.sdk.CustomerIO;

public class SampleApplication extends Application {
    private static final String SPIKE_TAG = "SegmentStorageSpike";
    private final ApplicationGraph appGraph = new ApplicationGraph(this);

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize Customer.io SDK on app start
        appGraph.getCustomerIORepository().initializeSdk(SampleApplication.this);

        // Encryption-at-rest Phase 3 spike: plant uniquely-named needles via the
        // public CIO/Segment API so we can grep the on-disk data dir afterwards.
        // Spike branch only.
        runSegmentStorageSpike();
    }

    public ApplicationGraph getApplicationGraph() {
        return appGraph;
    }

    private void runSegmentStorageSpike() {
        try {
            CustomerIO sdk = CustomerIO.instance();
            String uuid = UUID.randomUUID().toString();

            Map<String, String> identifyTraits = new HashMap<>();
            identifyTraits.put("SPIKE_TRAIT_email", "SPIKE_TRAIT_VALUE_" + uuid);
            sdk.identify("SPIKE_USER_" + uuid, identifyTraits);

            Map<String, String> trackProps1 = new HashMap<>();
            trackProps1.put("SPIKE_PROP_marker", "SPIKE_PROP_VALUE_1");
            sdk.track("SPIKE_EVENT_1", trackProps1);

            Map<String, String> trackProps2 = new HashMap<>();
            trackProps2.put("SPIKE_PROP_marker", "SPIKE_PROP_VALUE_2");
            sdk.track("SPIKE_EVENT_2", trackProps2);

            Map<String, String> trackProps3 = new HashMap<>();
            trackProps3.put("SPIKE_PROP_marker", "SPIKE_PROP_VALUE_3");
            sdk.track("SPIKE_EVENT_3", trackProps3);

            Map<String, String> screenProps = new HashMap<>();
            screenProps.put("SPIKE_PROP_marker", "SPIKE_PROP_VALUE_screen");
            sdk.screen("SPIKE_SCREEN_1", screenProps);

            // group(...) and alias(...) are not on the public CIO API — skipped.

            sdk.clearIdentify();

            Log.i(SPIKE_TAG, "spike sequence complete; uuid=" + uuid);
        } catch (Throwable t) {
            Log.e(SPIKE_TAG, "spike sequence failed", t);
        }
    }
}
