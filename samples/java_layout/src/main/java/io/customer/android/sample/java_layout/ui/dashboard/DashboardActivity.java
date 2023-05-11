package io.customer.android.sample.java_layout.ui.dashboard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

import com.google.android.material.snackbar.Snackbar;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.core.Randoms;
import io.customer.android.sample.java_layout.databinding.ActivityDashboardBinding;
import io.customer.android.sample.java_layout.ui.common.SimpleFragmentActivity;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.login.LoginActivity;
import io.customer.android.sample.java_layout.ui.settings.SettingsActivity;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;
import io.customer.sdk.CustomerIO;

public class DashboardActivity extends BaseActivity<ActivityDashboardBinding> {

    private AuthViewModel authViewModel;

    @Override
    protected ActivityDashboardBinding inflateViewBinding() {
        return ActivityDashboardBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void injectDependencies() {
        authViewModel = viewModelProvider.get(AuthViewModel.class);
    }

    @Override
    protected void setupContent() {
        validateAuth();
        setupViews();
        setupObservers();
    }

    private void validateAuth() {
        // Set up an OnPreDrawListener to the root view.
        final View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        Boolean isLoggedIn = authViewModel.getUserLoggedInStateObservable().getValue();
                        if (isLoggedIn == null) {
                            return false;
                        }

                        content.getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    }
                }
        );
    }

    private void setupViews() {
        binding.settingsButton.setOnClickListener(view -> {
            startActivity(new Intent(DashboardActivity.this, SettingsActivity.class));
        });
        binding.sendRandomEventButton.setOnClickListener(view -> {
            sendRandomEvent();
        });
        binding.sendCustomEventButton.setOnClickListener(view -> {
            startSimpleFragmentActivity(SimpleFragmentActivity.FRAGMENT_CUSTOM_TRACKING_EVENT);
        });
        binding.setDeviceAttributesButton.setOnClickListener(view -> {
            startSimpleFragmentActivity(SimpleFragmentActivity.FRAGMENT_DEVICE_ATTRIBUTES);
        });
        binding.setProfileAttributesButton.setOnClickListener(view -> {
            startSimpleFragmentActivity(SimpleFragmentActivity.FRAGMENT_PROFILE_ATTRIBUTES);
        });
        binding.logoutButton.setOnClickListener(view -> {
            authViewModel.clearLoggedInUser();
        });
    }

    private void setupObservers() {
        // TODO: Fetch and set user agent here
        binding.userAgentTextView.setText("User agent will be shown here");
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
        });
        authViewModel.getUserDataObservable().observe(this, user -> {
            binding.greetingsTextView.setText(
                    getString(R.string.dashboard_greeting_message_format, user.getDisplayName())
            );
        });
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
            if (isLoggedIn) {
                binding.progressIndicator.hide();
                binding.content.setVisibility(View.VISIBLE);
            } else {
                startActivity(new Intent(DashboardActivity.this, LoginActivity.class));
                finish();
            }
        });
    }

    private void sendRandomEvent() {
        Randoms randoms = new Randoms();
        String eventName = randoms.eventName();
        Map<String, Object> eventAttributes = randoms.eventAttributes();
        Map<String, String> extras = new HashMap<>();
        for (Map.Entry<String, Object> entry : eventAttributes.entrySet()) {
            extras.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        CustomerIO.instance().track(eventName, extras);
        Snackbar.make(binding.sendRandomEventButton,
                getString(R.string.event_tracked_msg_format, eventName),
                Snackbar.LENGTH_SHORT).show();
    }

    private void startSimpleFragmentActivity(String fragmentName) {
        Intent intent = new Intent(DashboardActivity.this, SimpleFragmentActivity.class);
        Bundle extras = SimpleFragmentActivity.getExtras(fragmentName);
        intent.putExtras(extras);
        startActivity(intent);
    }
}
