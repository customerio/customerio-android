package io.customer.android.sample.java_layout.ui.login;

import android.content.Intent;

import io.customer.android.sample.java_layout.data.model.User;
import io.customer.android.sample.java_layout.databinding.ActivityLoginBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;

public class LoginActivity extends BaseActivity<ActivityLoginBinding> {

    private AuthViewModel authViewModel;

    @Override
    protected ActivityLoginBinding inflateViewBinding() {
        return ActivityLoginBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void injectDependencies() {
        authViewModel = viewModelProvider.get(AuthViewModel.class);
    }

    @Override
    protected void setupContent() {
        setupViews();
        setupObservers();
    }

    private void setupObservers() {
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
        });
        authViewModel.getUserDataObservable().observe(this, user -> {
        });
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
            if (isLoggedIn) {
                startActivity(new Intent(LoginActivity.this, DashboardActivity.class));
                finish();
            }
        });
    }

    private void setupViews() {
        binding.loginButton.setOnClickListener(view -> {
            authViewModel.setLoggedInUser(new User("test@em.com", "Test User"));
        });
    }
}
