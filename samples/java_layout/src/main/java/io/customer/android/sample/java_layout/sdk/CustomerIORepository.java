package io.customer.android.sample.java_layout.sdk;

import android.os.StrictMode;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.Map;

import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.di.ApplicationGraph;
import io.customer.android.sample.java_layout.support.Optional;
import io.customer.messaginginapp.MessagingInAppModuleConfig;
import io.customer.messaginginapp.ModuleMessagingInApp;
import io.customer.messagingpush.ModuleMessagingPushFCM;
import io.customer.sdk.CustomerIO;
import io.customer.sdk.CustomerIOBuilder;

/**
 * Repository class to hold all Customer.io related operations at single place
 */
public class CustomerIORepository {
    public void initializeSdk(SampleApplication application) {
        ApplicationGraph appGraph = application.getApplicationGraph();
        // Get desired SDK config, only required by sample app
        final CustomerIOSDKConfig sdkConfig = getSdkConfig(appGraph.getPreferencesDataStore());

        // Enable strict mode after config
        enableStrictMode();

        // Initialize Customer.io SDK builder
        CustomerIOBuilder builder = new CustomerIOBuilder(application, sdkConfig.getCdpApiKey());

        // Modify SDK settings for testing purposes only.
        // If you don't need to override any of these settings, you can skip this line.
        configureSdk(builder, sdkConfig);

        // Enable optional features of the SDK by adding desired modules.
        // Enables push notification
        builder.addCustomerIOModule(new ModuleMessagingPushFCM());

        // Enables in-app messages
        if (sdkConfig.isInAppMessagingEnabled()) {
            builder.addCustomerIOModule(new ModuleMessagingInApp(
                    new MessagingInAppModuleConfig.Builder(sdkConfig.getSiteId(), sdkConfig.getRegion())
                            .setEventListener(new InAppMessageEventListener(appGraph.getLogger()))
                            .build()
            ));
        }

        // Finally, build to finish initializing the SDK.
        builder.build();
    }

    /**
     * Makes modifications to SDK configurations. It is mainly for testing
     * purposes and may not be needed unless there is a need to override any
     * default configuration from the SDK.
     */
    private void configureSdk(CustomerIOBuilder builder, final CustomerIOSDKConfig sdkConfig) {
        builder.migrationSiteId(sdkConfig.getSiteId());

        final String apiHost = sdkConfig.getApiHost();
        if (!TextUtils.isEmpty(apiHost)) {
            builder.apiHost(apiHost);
        }

        final String cdnHost = sdkConfig.getCdnHost();
        if (!TextUtils.isEmpty(cdnHost)) {
            builder.cdnHost(cdnHost);
        }

        if (sdkConfig.isTestModeEnabled()) {
            builder.flushAt(1);
        }

        builder.autoTrackActivityScreens(sdkConfig.isScreenTrackingEnabled());
        builder.autoTrackDeviceAttributes(sdkConfig.isDeviceAttributesTrackingEnabled());
        builder.trackApplicationLifecycleEvents(sdkConfig.isApplicationLifecycleTrackingEnabled());
        builder.region(sdkConfig.getRegion());
        builder.logLevel(sdkConfig.getLogLevel());
        builder.screenViewUse(sdkConfig.getScreenViewUse());
    }

    public void identify(@NonNull String email, @NonNull Map<String, String> attributes) {
        CustomerIO.instance().identify(email, attributes);
    }

    public void clearIdentify() {
        CustomerIO.instance().clearIdentify();
    }

    public void trackEvent(@NonNull String eventName, @NonNull Map<String, String> extras) {
        CustomerIO.instance().track(eventName, extras);
    }

    public void setDeviceAttributes(@NonNull Map<String, String> attributes) {
        CustomerIO.instance().setDeviceAttributes(attributes);
    }

    public void setProfileAttributes(@NonNull Map<String, String> attributes) {
        CustomerIO.instance().setProfileAttributes(attributes);
    }

    /*
     *******************************************************
     * Code below this line is required by sample app only *
     *******************************************************
     */

    // Retrieves SDK settings from data store
    @NonNull
    private CustomerIOSDKConfig getSdkConfig(PreferencesDataStore dataStore) {
        try {
            Map<String, String> configBundle = dataStore.sdkConfig().blockingFirst();
            Optional<CustomerIOSDKConfig> sdkConfig = CustomerIOSDKConfig.fromMap(configBundle);
            if (sdkConfig.isPresent()) {
                return sdkConfig.get();
            }
        } catch (Exception ignored) {
            // Ignore exception if no configurations are available in data store
        }

        // Return default configurations if no configurations were saved in data store
        return CustomerIOSDKConfig.getDefaultConfigurations();
    }

    private void enableStrictMode() {
        // Thread policy - detects network calls, disk reads, custom slow calls on main thread
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectNetwork()   // Detect network calls on main thread
                .detectDiskReads() // Detect disk reads on main thread
                .detectDiskWrites() // Detect disk writes on main thread
                .detectCustomSlowCalls() // Detect custom slow calls marked with StrictMode.noteSlowCall()
                .penaltyLog()      // Log violations to LogCat
                //.penaltyDialog()   // Show dialog for violations (useful for debugging)
                .build());

        // VM policy - detects leaks and other VM-level violations
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects() // Detect leaked SQLite objects
                .detectLeakedClosableObjects() // Detect leaked closeable objects
                .detectActivityLeaks()         // Detect leaked activities
                .penaltyLog()
                .build());
    }
}
