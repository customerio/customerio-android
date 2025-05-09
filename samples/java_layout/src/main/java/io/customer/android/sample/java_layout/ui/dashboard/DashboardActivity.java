package io.customer.android.sample.java_layout.ui.dashboard;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Pair;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivityDashboardBinding;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.inline.InlineExamplesActivity;
import io.customer.android.sample.java_layout.ui.login.LoginActivity;
import io.customer.android.sample.java_layout.ui.settings.InternalSettingsActivity;
import io.customer.android.sample.java_layout.ui.settings.SettingsActivity;
import io.customer.android.sample.java_layout.ui.tracking.TrackingFragmentActivity;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;
import io.customer.android.sample.java_layout.utils.Randoms;
import io.customer.android.sample.java_layout.utils.ViewUtils;
import io.customer.sdk.CustomerIO;

public class DashboardActivity extends BaseActivity<ActivityDashboardBinding> {

    private AuthViewModel authViewModel;
    private CustomerIORepository customerIORepository;

    private final ActivityResultLauncher<Intent> notificationSettingsRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (isNotificationPermissionGranted()) {
                    showPushPermissionGranted();
                }
            });
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private final ActivityResultLauncher<String> notificationPermissionRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    showPushPermissionGranted();
                } else {
                    showPushPermissionDeniedAlert(R.string.notification_permission_denied);
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
        prepareViewsForAutomatedTests();
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

    private void prepareViewsForAutomatedTests() {
        ViewUtils.prepareForAutomatedTests(binding.settingsButton, R.string.acd_settings_icon);
        ViewUtils.prepareForAutomatedTests(binding.userEmailTextView, R.string.acd_email_id_text);
        ViewUtils.prepareForAutomatedTests(binding.deviceIdTextView, R.string.acd_device_id_text);
        ViewUtils.prepareForAutomatedTests(binding.sendRandomEventButton, R.string.acd_random_event_button);
        ViewUtils.prepareForAutomatedTests(binding.sendCustomEventButton, R.string.acd_custom_event_button);
        ViewUtils.prepareForAutomatedTests(binding.setDeviceAttributesButton, R.string.acd_device_attribute_button);
        ViewUtils.prepareForAutomatedTests(binding.setProfileAttributesButton, R.string.acd_profile_attribute_button);
        ViewUtils.prepareForAutomatedTests(binding.showPushPromptButton, R.string.acd_push_prompt_button);
        ViewUtils.prepareForAutomatedTests(binding.logoutButton, R.string.acd_logout_button);
    }

    private void setupViews() {
        binding.settingsButton.setOnClickListener(view -> {
            startActivity(new Intent(DashboardActivity.this, SettingsActivity.class));
        });
        binding.settingsButton.setOnLongClickListener(view -> {
            startActivity(new Intent(DashboardActivity.this, InternalSettingsActivity.class));
            return true;
        });
        String deviceToken = CustomerIO.instance().getRegisteredDeviceToken();
        if (deviceToken != null && !deviceToken.isEmpty()) {
            binding.deviceIdTextView.setClickable(true);
            binding.deviceIdTextView.setFocusable(true);
            binding.deviceIdTextView.setText(deviceToken);
            binding.deviceIdTextView.setOnClickListener(view -> {
                copyToClipboard(binding.deviceIdTextView.getText().toString().trim());
            });
        } else {
            binding.deviceIdTextView.setClickable(false);
            binding.deviceIdTextView.setFocusable(false);
            binding.deviceIdTextView.setText(R.string.device_token_not_registered);
        }
        binding.sendRandomEventButton.setOnClickListener(view -> {
            sendRandomEvent();
        });
        binding.sendCustomEventButton.setOnClickListener(view -> {
            startTrackingFragmentActivity(TrackingFragmentActivity.FRAGMENT_CUSTOM_TRACKING_EVENT);
        });
        binding.setDeviceAttributesButton.setOnClickListener(view -> {
            startTrackingFragmentActivity(TrackingFragmentActivity.FRAGMENT_DEVICE_ATTRIBUTES);
        });
        binding.setProfileAttributesButton.setOnClickListener(view -> {
            startTrackingFragmentActivity(TrackingFragmentActivity.FRAGMENT_PROFILE_ATTRIBUTES);
        });
        binding.showPushPromptButton.setOnClickListener(view -> {
            requestNotificationPermission();
        });
        binding.inlineExamplesXmlLayoutButton.setOnClickListener(view -> {
            startInlineExamplesActivity(InlineExamplesActivity.FRAGMENT_ANDROID_XML);
        });
        binding.inlineExamplesComposeButton.setOnClickListener(view -> {
            startInlineExamplesActivity(InlineExamplesActivity.FRAGMENT_COMPOSE);
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
        Snackbar.make(binding.sendRandomEventButton,
                R.string.event_tracked_msg,
                Snackbar.LENGTH_SHORT).show();
    }

    private void startTrackingFragmentActivity(String fragmentName) {
        Intent intent = new Intent(DashboardActivity.this, TrackingFragmentActivity.class);
        Bundle extras = TrackingFragmentActivity.getExtras(fragmentName);
        intent.putExtras(extras);
        startActivity(intent);
    }

    private void startInlineExamplesActivity(String fragmentName) {
        Intent intent = new Intent(DashboardActivity.this, InlineExamplesActivity.class);
        Bundle extras = InlineExamplesActivity.getExtras(fragmentName);
        intent.putExtras(extras);
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (isNotificationPermissionGranted()) {
            // Ask for notification permission if not granted
            showPushPermissionGrantedAlert();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            // If notification permission is not available or denied permanently, show prompt to open settings
            showPushPermissionDeniedAlert(R.string.notification_permission_failure);
        } else {
            // Else, request notification permission
            notificationPermissionRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private boolean isNotificationPermissionGranted() {
        // Push notification permission is only required by API Level 33 (Android 13) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(DashboardActivity.this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        // For Android OS 12 and below, notification enabled status can be checked using NotificationManagerCompat
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private void openNotificationPermissionSettings() {
        final Intent intent;
        final String packageName = getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
        }
        notificationSettingsRequestLauncher.launch(intent);
    }

    private void showPushPermissionGranted() {
        Snackbar.make(binding.showPushPromptButton,
                R.string.notification_permission_success,
                Snackbar.LENGTH_SHORT).show();
    }

    private void showPushPermissionGrantedAlert() {
        MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(this);
        builder.setTitle(R.string.notification_permission_alert_title);
        builder.setMessage(R.string.notification_permission_success);
        builder.show();
    }

    private void showPushPermissionDeniedAlert(@StringRes int messageResId) {
        MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(this);
        builder.setMessage(messageResId);
        builder.setNeutralButton(R.string.open_settings, (dialogInterface, i) -> openNotificationPermissionSettings());
        builder.show();
    }

    private void copyToClipboard(String text) {
        if (text.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(getString(R.string.device_token), text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show();
    }
}
