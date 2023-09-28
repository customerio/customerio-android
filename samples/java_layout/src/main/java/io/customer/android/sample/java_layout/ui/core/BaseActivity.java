package io.customer.android.sample.java_layout.ui.core;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.EmptySuper;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.di.ApplicationGraph;

public abstract class BaseActivity<VB extends ViewBinding> extends AppCompatActivity {

    protected VB binding;
    protected ApplicationGraph applicationGraph;
    protected ViewModelProvider viewModelProvider;

    protected abstract VB inflateViewBinding();

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ((SampleApplication) getApplication()).lifecycleEventsListener.logEvent("onNewIntent", this);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applicationGraph = ((SampleApplication) getApplication()).getApplicationGraph();
        binding = inflateViewBinding();
        setContentView(binding.getRoot());
        viewModelProvider = new ViewModelProvider(this, applicationGraph.getViewModelFactory());
        injectDependencies();
        setupContent();
    }

    @EmptySuper
    protected void injectDependencies() {
    }

    @EmptySuper
    protected void setupContent() {
    }
}
