package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.android.material.snackbar.Snackbar;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.utils.OSUtils;
import io.customer.android.sample.java_layout.utils.StringUtils;
import io.customer.android.sample.java_layout.utils.ViewUtils;
import io.customer.messagingpush.provider.FCMTokenProviderImpl;
import io.customer.sdk.CustomerIOShared;
import io.customer.sdk.device.DeviceTokenProvider;
import io.customer.sdk.util.Logger;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class SettingsActivity extends BaseActivity<ActivitySettingsBinding> {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private SettingsViewModel settingsViewModel;

    @Override
    protected ActivitySettingsBinding inflateViewBinding() {
        return ActivitySettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void injectDependencies() {
        settingsViewModel = viewModelProvider.get(SettingsViewModel.class);
    }

    @Override
    protected void onDestroy() {
        disposables.dispose();
        super.onDestroy();
    }

    @Override
    protected void setupContent() {
        setupViews();
        setupObservers();
    }

    private void setupViews() {
        binding.topAppBar.setNavigationOnClickListener(view -> {
            // For better user experience, navigate to launcher activity on navigate up button
            if (isTaskRoot()) {
                startActivity(new Intent(SettingsActivity.this, DashboardActivity.class));
            }
            onBackPressed();
        });
        binding.deviceTokenInputLayout.setEndIconOnClickListener(view -> {
            String deviceToken = ViewUtils.getTextTrimmed(binding.deviceTokenTextInput);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.device_token), deviceToken);
            clipboard.setPrimaryClip(clip);
        });
        binding.saveButton.setOnClickListener(view -> {
            String siteId = ViewUtils.getTextTrimmed(binding.siteIdTextInput);
            String apiKey = ViewUtils.getTextTrimmed(binding.apiKeyTextInput);
            boolean isFormValid = true;
            if (TextUtils.isEmpty(siteId)) {
                binding.siteIdInputLayout.setError(getString(R.string.error_site_id));
                isFormValid = false;
            }
            if (TextUtils.isEmpty(apiKey)) {
                binding.apiKeyInputLayout.setError(getString(R.string.error_api_key));
                isFormValid = false;
            }

            if (isFormValid) {
                binding.progressIndicator.show();
                String trackingURL = ViewUtils.getTextTrimmed(binding.trackingUrlTextInput);
                Integer bqSecondsDelay = StringUtils.parseInteger(ViewUtils.getTextTrimmed(binding.bqDelayTextInput), null);
                Integer bqMinTasks = StringUtils.parseInteger(ViewUtils.getTextTrimmed(binding.bqTasksTextInput), null);
                boolean featInApp = binding.enableInAppSwitch.isChecked();
                boolean featTrackScreens = binding.trackScreensSwitch.isChecked();
                boolean featTrackDeviceAttributes = binding.trackDeviceAttributesSwitch.isChecked();
                boolean featDebugMode = binding.debugModeSwitch.isChecked();
                CustomerIOSDKConfig config = new CustomerIOSDKConfig(siteId,
                        apiKey,
                        trackingURL,
                        bqSecondsDelay,
                        bqMinTasks,
                        featInApp,
                        featTrackScreens,
                        featTrackDeviceAttributes,
                        featDebugMode);
                Disposable disposable = settingsViewModel
                        .updateConfigurations(config)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(preferences -> {
                            binding.progressIndicator.hide();
                            Snackbar.make(binding.saveButton, R.string.settings_save_msg, Snackbar.LENGTH_SHORT).show();
                            OSUtils.restartApp();
                        });
                disposables.add(disposable);
            }
        });
        binding.restoreDefaultsButton.setOnClickListener(view -> updateIOWithConfig(CustomerIOSDKConfig.getDefaultConfigurations()));
    }

    private void setupObservers() {
        // TODO: Expose SDK method to get device token and replace here
        Logger cioSdkLogger = CustomerIOShared.instance().getDiStaticGraph().getLogger();
        DeviceTokenProvider fcmTokenProvider = new FCMTokenProviderImpl(cioSdkLogger, this);
        fcmTokenProvider.getCurrentToken(token -> {
            binding.deviceTokenTextInput.setText(token);
            return null;
        });
        settingsViewModel.getSDKConfigObservable().observe(this, config -> {
            binding.progressIndicator.hide();
            updateIOWithConfig(config);
        });
    }

    private void updateIOWithConfig(@NonNull CustomerIOSDKConfig config) {
        ViewUtils.setTextWithSelectionIfFocused(binding.trackingUrlTextInput, config.getTrackingURL());
        ViewUtils.setTextWithSelectionIfFocused(binding.siteIdTextInput, config.getSiteId());
        ViewUtils.setTextWithSelectionIfFocused(binding.apiKeyTextInput, config.getApiKey());
        ViewUtils.setTextWithSelectionIfFocused(binding.bqDelayTextInput, StringUtils.fromInteger(config.getBackgroundQueueSecondsDelay()));
        ViewUtils.setTextWithSelectionIfFocused(binding.bqTasksTextInput, StringUtils.fromInteger(config.getBackgroundQueueMinNumOfTasks()));
        binding.enableInAppSwitch.setChecked(config.inAppEnabled());
        binding.trackScreensSwitch.setChecked(config.screenTrackingEnabled());
        binding.trackDeviceAttributesSwitch.setChecked(config.deviceAttributesTrackingEnabled());
        binding.debugModeSwitch.setChecked(config.debugModeEnabled());
    }
}
