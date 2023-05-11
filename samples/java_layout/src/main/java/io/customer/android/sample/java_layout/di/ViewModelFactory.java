package io.customer.android.sample.java_layout.di;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import io.customer.android.sample.java_layout.data.PreferencesDataStore;
import io.customer.android.sample.java_layout.ui.settings.SettingsViewModel;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;

public class ViewModelFactory implements ViewModelProvider.Factory {

    private final PreferencesDataStore preferencesDataStore;

    public ViewModelFactory(PreferencesDataStore preferencesDataStore) {
        this.preferencesDataStore = preferencesDataStore;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(AuthViewModel.class)) {
            return (T) new AuthViewModel(preferencesDataStore);
        } else if (modelClass.isAssignableFrom(SettingsViewModel.class)) {
            return (T) new SettingsViewModel(preferencesDataStore);
        } else {
            throw new IllegalArgumentException("Unknown ViewModel class");
        }
    }
}
