package io.customer.android.sample.java_layout.ui.settings;

import androidx.annotation.Nullable;
import androidx.datastore.preferences.core.Preferences;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.ViewModel;

import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.support.Optional;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

public class SettingsViewModel extends ViewModel {
    private final PreferencesDataStore preferencesDataStore;
    private final LiveData<CustomerIOSDKConfig> sdkConfigObservable;

    public SettingsViewModel(PreferencesDataStore preferencesDataStore) {
        this.preferencesDataStore = preferencesDataStore;
        Flowable<Optional<CustomerIOSDKConfig>> publisher = preferencesDataStore.sdkConfig().map(CustomerIOSDKConfig::fromMap);
        this.sdkConfigObservable = LiveDataReactiveStreams.fromPublisher(
                publisher.map(customerIOSDKConfigOptional -> {
                    CustomerIOSDKConfig config = customerIOSDKConfigOptional.get();
                    return config == null ? CustomerIOSDKConfig.getDefaultConfigurations() : config;
                }));
    }

    public LiveData<CustomerIOSDKConfig> getSDKConfigObservable() {
        return sdkConfigObservable;
    }

    public Single<Preferences> updateConfigurations(@Nullable CustomerIOSDKConfig sdkConfig) {
        if (sdkConfig == null) {
            return preferencesDataStore.clearSDKConfig();
        } else {
            return preferencesDataStore.saveToSDKConfig(CustomerIOSDKConfig.toMap(sdkConfig));
        }
    }
}
