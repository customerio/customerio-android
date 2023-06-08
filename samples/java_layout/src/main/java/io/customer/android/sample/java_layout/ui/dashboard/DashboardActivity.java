package io.customer.android.sample.java_layout.ui.dashboard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityDashboardBinding;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.ui.common.SimpleFragmentActivity;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.login.LoginActivity;
import io.customer.android.sample.java_layout.ui.settings.SettingsActivity;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;
import io.customer.android.sample.java_layout.utils.Randoms;
import io.customer.android.sample.java_layout.utils.ViewUtils;

public class DashboardActivity extends BaseActivity<ActivityDashboardBinding> {

    private AuthViewModel authViewModel;
    private CustomerIORepository customerIORepository;

    private final ActivityResultLauncher<String> notificationPermissionRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    onPushPermissionGranted();
                } else {
                    MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(this);
                    builder.setMessage(R.string.notification_permission_failure);
                    builder.show();
                }
            });

    @Override
    protected ActivityDashboardBinding inflateViewBinding() {
        return ActivityDashboardBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void injectDependencies() {
        authViewModel = viewModelProvider.get(AuthViewModel.class);
        customerIORepository = applicationGraph.getCustomerIORepository();
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
        binding.showPushPromptButton.setOnClickListener(view -> {
            requestNotificationPermission();
        });
        binding.logoutButton.setOnClickListener(view -> {
            authViewModel.clearLoggedInUser();
        });
    }

    private void setupObservers() {
        ViewUtils.setBuildInfo(binding.buildInfoTextView);
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
        });
        authViewModel.getUserDataObservable().observe(this, user -> {
            binding.userEmailTextView.setText(user.getEmail());
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
        Pair<String, Map<String, Object>> trackingEvent = randoms.trackingEvent();
        String eventName = trackingEvent.first;
        Map<String, Object> eventAttributes = trackingEvent.second;

        Map<String, String> extras = new HashMap<>();
        if (eventAttributes != null) {
            for (Map.Entry<String, Object> entry : eventAttributes.entrySet()) {
                extras.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        customerIORepository.trackEvent(eventName, extras);
        MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(this);
        builder.setMessage(getString(R.string.event_tracked_msg_format, eventName));
        builder.show();
    }

    private void startSimpleFragmentActivity(String fragmentName) {
        Intent intent = new Intent(DashboardActivity.this, SimpleFragmentActivity.class);
        Bundle extras = SimpleFragmentActivity.getExtras(fragmentName);
        intent.putExtras(extras);
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        // Push notification permission is only required by API Level 33 (Android 13) and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onPushPermissionGranted();
            return;
        }

        int permissionStatus = ContextCompat.checkSelfPermission(
                DashboardActivity.this, Manifest.permission.POST_NOTIFICATIONS);
        // Ask for notification permission if not granted
        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            onPushPermissionGranted();
        } else {
            notificationPermissionRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void onPushPermissionGranted() {
        MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(this);
        builder.setMessage(R.string.notification_permission_success);
        builder.show();
    }
}
