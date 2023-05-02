package io.customer.android.sample.java_layout.di;

import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.data.PreferencesDataStore;

public class ApplicationGraph {
    private final SampleApplication application;
    private final PreferencesDataStore preferencesDataStore;
    private final ViewModelFactory viewModelFactory;

    public ApplicationGraph(SampleApplication application) {
        this.application = application;
        preferencesDataStore = new PreferencesDataStore(application);
        viewModelFactory = new ViewModelFactory(preferencesDataStore);
    }

    public SampleApplication getApplication() {
        return application;
    }

    public PreferencesDataStore getPreferencesDataStore() {
        return preferencesDataStore;
    }

    public ViewModelFactory getViewModelFactory() {
        return viewModelFactory;
    }
}
