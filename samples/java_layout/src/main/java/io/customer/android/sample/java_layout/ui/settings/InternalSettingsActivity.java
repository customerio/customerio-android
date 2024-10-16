package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.databinding.ActivityInternalSettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.utils.OSUtils;
import io.customer.android.sample.java_layout.utils.ViewUtils;
import io.customer.sdk.CustomerIO;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class InternalSettingsActivity extends BaseActivity<ActivityInternalSettingsBinding> {

    private SettingsViewModel settingsViewModel;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Override
    protected ActivityInternalSettingsBinding inflateViewBinding() {
        return ActivityInternalSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void injectDependencies() {
        settingsViewModel = viewModelProvider.get(SettingsViewModel.class);
    }

    @Override
    protected void setupContent() {
        setUpObservers();
        setUpActions();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disposables.dispose();
    }

    private void setUpObservers() {
        binding.settingsDeviceTokenLabel.setText(CustomerIO.instance().getRegisteredDeviceToken());
        settingsViewModel.getSDKConfigObservable().observe(this, config -> {
            binding.progressIndicator.hide();
            updateUiWithConfig(config);
        });
    }

    private void updateUiWithConfig(@NonNull CustomerIOSDKConfig config) {
        ViewUtils.setTextWithSelectionIfFocused(binding.settingsApiHostLabel, config.getApiHost());
        ViewUtils.setTextWithSelectionIfFocused(binding.settingsCdnHostLabel, config.getCdnHost());
    }

    private void setUpActions() {
        binding.topAppBar.setNavigationOnClickListener(view -> {
            // For better user experience, navigate to launcher activity on navigate up button
            if (isTaskRoot()) {
                startActivity(new Intent(InternalSettingsActivity.this, DashboardActivity.class));
            }
            onBackPressed();
        });
        binding.settingsDeviceTokenLayout.setEndIconOnClickListener(view -> {
            String deviceToken = ViewUtils.getTextTrimmed(binding.settingsDeviceTokenLabel);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.device_token), deviceToken);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show();
        });
        binding.settingsSaveButton.setOnClickListener(v -> saveSettings());
        binding.settingsRestoreDefaultsButton.setOnClickListener(v -> updateUiWithConfig(CustomerIOSDKConfig.getDefaultConfigurations()));
        ViewUtils.clearErrorWhenTextedEntered(binding.settingsApiHostLabel, binding.settingsApiHostLayout);
        ViewUtils.clearErrorWhenTextedEntered(binding.settingsCdnHostLabel, binding.settingsCdnHostLayout);
    }

    private void saveSettings() {
        CustomerIOSDKConfig currentSettings = settingsViewModel.getSDKConfigObservable().getValue();
        if (currentSettings == null) {
            Toast.makeText(this, "Error! Cannot save settings!", Toast.LENGTH_SHORT).show();
            return;
        }

        String apiHost = ViewUtils.getTextTrimmed(binding.settingsApiHostLabel);
        String cdnHost = ViewUtils.getTextTrimmed(binding.settingsCdnHostLabel);
        if (!validateUiInputs(apiHost, cdnHost)) {
            return;
        }

        CustomerIOSDKConfig newSettings = createNewSettings(currentSettings, apiHost, cdnHost);
        settingsViewModel.updateConfigurations(newSettings);

        binding.progressIndicator.show();
        Disposable disposable = settingsViewModel
                .updateConfigurations(newSettings)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(preferences -> {
                    binding.progressIndicator.hide();
                    Toast.makeText(this, R.string.settings_save_msg, Toast.LENGTH_SHORT).show();
                    OSUtils.restartApp();
                });
        disposables.add(disposable);
    }

    private boolean validateUiInputs(@NonNull String apiHost, @NonNull String cdnHost) {
        boolean valid = true;
        if (isHostURLInvalid(apiHost)) {
            valid = false;
            ViewUtils.setError(binding.settingsApiHostLayout, getString(R.string.error_url_input_field));
        }
        if (isHostURLInvalid(cdnHost)) {
            valid = false;
            ViewUtils.setError(binding.settingsCdnHostLayout, getString(R.string.error_url_input_field));
        }
        return valid;
    }

    @NonNull
    private static CustomerIOSDKConfig createNewSettings(CustomerIOSDKConfig currentSettings, String apiHost, String cdnHost) {
        return new CustomerIOSDKConfig(
                currentSettings.getCdpApiKey(),
                currentSettings.getSiteId(),
                apiHost,
                cdnHost,
                currentSettings.isScreenTrackingEnabled(),
                currentSettings.isDeviceAttributesTrackingEnabled(),
                currentSettings.getLogLevel(),
                currentSettings.getRegion(),
                currentSettings.isApplicationLifecycleTrackingEnabled(),
                currentSettings.isTestModeEnabled(),
                currentSettings.isInAppMessagingEnabled()
        );
    }

    private boolean isHostURLInvalid(String url) {
        // Empty text is not considered valid
        if (TextUtils.isEmpty(url)) {
            return true;
        }

        try {
            Uri uri = Uri.parse(url);
            // Since SDK does not support custom schemes, we manually append http:// to the URL
            // So the URL is considered invalid if it ends with a slash, contains a scheme, query or fragment
            return url.endsWith("/")
                    || !TextUtils.isEmpty(uri.getScheme())
                    || !TextUtils.isEmpty(uri.getQuery())
                    || !TextUtils.isEmpty(uri.getFragment());
        } catch (Exception ex) {
            //noinspection CallToPrintStackTrace
            ex.printStackTrace();
            return true;
        }
    }
}
