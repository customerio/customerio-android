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
import io.customer.android.sample.java_layout.utils.Randoms;
import io.customer.android.sample.java_layout.utils.ViewUtils;

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
        prepareViewsForAutomatedTests();
        setupViews();
        setupObservers();
    }

    private void prepareViewsForAutomatedTests() {
        ViewUtils.prepareForAutomatedTests(binding.settingsButton, R.string.acd_settings_icon);
        ViewUtils.prepareForAutomatedTests(binding.displayNameTextInput, R.string.acd_first_name_input);
        ViewUtils.prepareForAutomatedTests(binding.emailInputLayout, R.string.acd_email_input);
        ViewUtils.prepareForAutomatedTests(binding.loginButton, R.string.acd_login_button);
        ViewUtils.prepareForAutomatedTests(binding.randomLoginButton, R.string.acd_random_login_button);
    }

    private void setupObservers() {
        ViewUtils.setBuildInfo(binding.buildInfoTextView);
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
            if (isLoggedIn) {
                startActivity(new Intent(LoginActivity.this, DashboardActivity.class));
                finish();
            }
        });
    }

    private void setupViews() {
        binding.settingsButton.setOnClickListener(view -> {
            startActivity(new Intent(LoginActivity.this, SettingsActivity.class));
        });
        binding.loginButton.setOnClickListener(view -> {
            boolean isFormValid = true;
            String displayName = ViewUtils.getText(binding.displayNameTextInput);
            String email = ViewUtils.getTextTrimmed(binding.emailTextInput);
            if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                ViewUtils.setError(binding.emailInputLayout, getString(R.string.error_email));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.emailInputLayout, null);
            }

            if (isFormValid) {
                authViewModel.setLoggedInUser(new User(email, displayName, false));
            }
        });
        binding.randomLoginButton.setOnClickListener(view -> {
            Randoms randoms = new Randoms();
            authViewModel.setLoggedInUser(new User(randoms.email(), "", true));
        });
    }
}
