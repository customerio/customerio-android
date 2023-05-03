package io.customer.android.sample.java_layout.data;


import androidx.annotation.MainThread;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.Map;

import io.customer.android.sample.java_layout.SampleApplication;
import io.reactivex.rxjava3.core.Flowable;

public class PreferencesDataStore {
    private static final String SDK_CONFIG_FILE = "sdk_config_prefs";
    private static final String USER_DATA_FILE = "user_data_prefs";

    private final RxDataStore<Preferences> sdkDataStore;
    private final RxDataStore<Preferences> userDataStore;

    public PreferencesDataStore(SampleApplication application) {
        sdkDataStore = new RxPreferenceDataStoreBuilder(application, SDK_CONFIG_FILE).build();
        userDataStore = new RxPreferenceDataStoreBuilder(application, USER_DATA_FILE).build();
    }

    @MainThread
    public void clearSDKConfig() {
        PreferencesStoreUtils.clearData(sdkDataStore);
    }

    @MainThread
    public void saveToSDKConfig(Map<String, String> bundle) {
        PreferencesStoreUtils.saveData(sdkDataStore, bundle);
    }

    @MainThread
    public Flowable<Map<String, String>> sdkConfig() {
        return PreferencesStoreUtils.getDataAsFlowable(sdkDataStore);
    }

    @MainThread
    public void clearUserData() {
        PreferencesStoreUtils.clearData(userDataStore);
    }

    @MainThread
    public void saveToUserData(Map<String, String> bundle) {
        PreferencesStoreUtils.saveData(userDataStore, bundle);
    }

    @MainThread
    public Flowable<Map<String, String>> userData() {
        return PreferencesStoreUtils.getDataAsFlowable(userDataStore);
    }
}
