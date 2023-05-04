package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.android.material.snackbar.Snackbar;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.core.StringUtils;
import io.customer.android.sample.java_layout.core.ViewUtils;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.messagingpush.provider.FCMTokenProviderImpl;
import io.customer.sdk.CustomerIOShared;
import io.customer.sdk.device.DeviceTokenProvider;
import io.customer.sdk.util.Logger;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class SettingsActivity extends BaseActivity<ActivitySettingsBinding> {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private SettingsViewModel settingsViewModel;

    @Override
    protected ActivitySettingsBinding inflateViewBinding() {
        return ActivitySettingsBinding.inflate(getLayoutInflater());
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
        binding.topAppBar.setNavigationOnClickListener(view -> finish());
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
                disposables.add(settingsViewModel.updateConfigurations(config).subscribe(preferences -> {
                    binding.progressIndicator.hide();
                    Snackbar.make(binding.saveButton, R.string.settings_save_msg, Snackbar.LENGTH_SHORT).show();
                }));
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
        binding.enableInAppSwitch.setChecked(config.isInAppEnabled());
        binding.trackScreensSwitch.setChecked(config.isScreenTrackingEnabled());
        binding.trackDeviceAttributesSwitch.setChecked(config.isDeviceAttributesTrackingEnabled());
        binding.debugModeSwitch.setChecked(config.isDebugModeEnabled());
    }
}
