package io.customer.android.sample.java_layout.ui.login;

import android.content.Intent;
import android.text.TextUtils;
import android.util.Patterns;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.data.model.User;
import io.customer.android.sample.java_layout.databinding.ActivityLoginBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.ui.settings.SettingsActivity;
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
            if (isLoggedIn) {
                startActivity(new Intent(LoginActivity.this, DashboardActivity.class));
                finish();
            }
        });
    }

    private void setupViews() {
        binding.userAgentTextView.setText("User agent will be shown here");
        binding.settingsButton.setOnClickListener(view -> {
            startActivity(new Intent(LoginActivity.this, SettingsActivity.class));
        });
        binding.loginButton.setOnClickListener(view -> {
            boolean isFormValid = true;
            //noinspection ConstantConditions
            String displayName = binding.displayNameTextInput.getText().toString().trim();
            if (TextUtils.isEmpty(displayName)) {
                binding.displayNameInputLayout.setError(getString(R.string.error_display_name));
                isFormValid = false;
            }

            //noinspection ConstantConditions
            String email = binding.emailTextInput.getText().toString().trim();
            if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.emailInputLayout.setError(getString(R.string.error_email));
                isFormValid = false;
            }

            if (isFormValid) {
                authViewModel.setLoggedInUser(new User(email, displayName, false));
            }
        });
        binding.randomLoginButton.setOnClickListener(view -> {
            authViewModel.setLoggedInUser(new User("test@em.com", "Test User", true));
        });
    }
}
