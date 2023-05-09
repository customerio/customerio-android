package io.customer.android.sample.java_layout.ui.core;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.EmptySuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewbinding.ViewBinding;

import io.customer.android.sample.java_layout.SampleApplication;
import io.customer.android.sample.java_layout.di.ApplicationGraph;

public abstract class BaseFragment<VB extends ViewBinding> extends Fragment {

    protected VB binding;
    protected ApplicationGraph applicationGraph;
    protected ViewModelProvider viewModelProvider;

    protected abstract VB inflateViewBinding();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //noinspection ConstantConditions
        applicationGraph = ((SampleApplication) getActivity().getApplication()).getApplicationGraph();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = inflateViewBinding();
        viewModelProvider = new ViewModelProvider(this, applicationGraph.getViewModelFactory());
        injectDependencies();
        setupContent();
        return binding.getRoot();
    }

    @EmptySuper
    protected void injectDependencies() {
    }

    @EmptySuper
    protected void setupContent() {
    }
}
