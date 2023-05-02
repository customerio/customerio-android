package io.customer.android.sample.java_layout.data;


import androidx.annotation.MainThread;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.preferences.rxjava3.RxPreferenceDataStoreBuilder;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.SampleApplication;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

public class PreferencesDataStore {
    private static final String USER_DATA_FILE = "user_data_prefs";

    private final RxDataStore<Preferences> dataStore;

    public PreferencesDataStore(SampleApplication application) {
        dataStore = new RxPreferenceDataStoreBuilder(application, USER_DATA_FILE).build();
    }

    @MainThread
    public void clearUserData() {
        dataStore.updateDataAsync(preferences -> {
            preferences.toMutablePreferences().clear();
            return Single.just(preferences);
        });
    }

    public Single<Preferences> saveToUserData(Map<String, String> bundle) {
        return dataStore.updateDataAsync(prefs -> {
            MutablePreferences mutable = prefs.toMutablePreferences();
            for (Map.Entry<String, String> entry : bundle.entrySet()) {
                mutable.set(PreferencesKeys.stringKey(entry.getKey()), entry.getValue());
            }
            return Single.just(mutable);
        });
    }

    @MainThread
    public Flowable<Map<String, String>> userData() {
        return dataStore.data().map(prefs -> {
            Map<Preferences.Key<?>, Object> prefsMap = prefs.asMap();
            Map<String, String> bundle = new HashMap<>();
            for (Map.Entry<Preferences.Key<?>, Object> entry : prefsMap.entrySet()) {
                Object value = entry.getValue();
                bundle.put(entry.getKey().getName(), value == null ? null : value.toString());
            }
            return bundle;
        });
    }
}
