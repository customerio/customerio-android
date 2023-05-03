package io.customer.android.sample.java_layout.data;


import androidx.annotation.MainThread;
import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.preferences.core.PreferencesKeys;
import androidx.datastore.rxjava3.RxDataStore;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

class PreferencesStoreUtils {
    @MainThread
    @SuppressWarnings("UnusedReturnValue")
    public static Single<Preferences> clearData(RxDataStore<Preferences> store) {
        return store.updateDataAsync(prefs -> {
            MutablePreferences mutable = prefs.toMutablePreferences();
            mutable.clear();
            return Single.just(mutable);
        });
    }

    @MainThread
    @SuppressWarnings("UnusedReturnValue")
    public static Single<Preferences> saveData(RxDataStore<Preferences> store, Map<String, String> bundle) {
        return store.updateDataAsync(prefs -> {
            MutablePreferences mutable = prefs.toMutablePreferences();
            for (Map.Entry<String, String> entry : bundle.entrySet()) {
                mutable.set(PreferencesKeys.stringKey(entry.getKey()), entry.getValue());
            }
            return Single.just(mutable);
        });
    }

    @MainThread
    public static Flowable<Map<String, String>> getDataAsFlowable(RxDataStore<Preferences> store) {
        return store.data().map(prefs -> {
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
