package io.customer.android.sample.java_layout.ui.core;

import android.os.Bundle;

import androidx.annotation.EmptySuper;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewbinding.ViewBinding;

public abstract class BaseActivity<VB extends ViewBinding> extends AppCompatActivity {

    protected VB binding;

    protected abstract VB inflateViewBinding();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = inflateViewBinding();
        setContentView(binding.getRoot());
        setupContent();
    }

    @EmptySuper
    protected void setupContent() {

    }
}
