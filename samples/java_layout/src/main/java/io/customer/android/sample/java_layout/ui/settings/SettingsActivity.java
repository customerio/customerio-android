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
    private boolean isLinkParamsPopulated = false;

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

    private void parseLinkParams() {
        // Exit early if params were already populated
        if (isLinkParamsPopulated) return;

        Intent intent = getIntent();
        Uri deepLinkUri = intent.getData();

        // deepLinkUri contains link URI if activity is launched from deep link or url from
        // Customer.io push notification
        // e.g.
        // java-sample://settings&site_id=xxx&api_key=yyy
        // https://www.java-sample.com/settings&site_id=xxx&api_key=yyy

        if (deepLinkUri != null) {
            String siteId = deepLinkUri.getQueryParameter("site_id");
            if (siteId != null) {
                ViewUtils.setTextWithSelectionIfFocused(binding.siteIdTextInput, siteId);
            }
            String apiKey = deepLinkUri.getQueryParameter("api_key");
            if (apiKey != null) {
                ViewUtils.setTextWithSelectionIfFocused(binding.apiKeyTextInput, apiKey);
            }
        }
        isLinkParamsPopulated = true;
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
            String trackingURL = ViewUtils.getTextTrimmed(binding.trackingUrlTextInput);

            boolean isFormValid = true;
            Uri trackingUri = Uri.parse(trackingURL);
            // Since SDK does not allow tracking URL with empty host
            if (!trackingURL.isEmpty() && TextUtils.isEmpty(trackingUri.getHost())) {
                ViewUtils.setError(binding.trackingUrlInputLayout, getString(R.string.error_tracking_url));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.trackingUrlInputLayout, null);
            }
            if (TextUtils.isEmpty(siteId)) {
                ViewUtils.setError(binding.siteIdInputLayout, getString(R.string.error_site_id));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.siteIdInputLayout, null);
            }
            if (TextUtils.isEmpty(apiKey)) {
                ViewUtils.setError(binding.apiKeyInputLayout, getString(R.string.error_api_key));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.apiKeyInputLayout, null);
            }

            if (isFormValid) {
                binding.progressIndicator.show();
                Double bqSecondsDelay = StringUtils.parseDouble(ViewUtils.getTextTrimmed(binding.bqDelayTextInput), null);
                Integer bqMinTasks = StringUtils.parseInteger(ViewUtils.getTextTrimmed(binding.bqTasksTextInput), null);
                boolean featTrackScreens = binding.trackScreensSwitch.isChecked();
                boolean featTrackDeviceAttributes = binding.trackDeviceAttributesSwitch.isChecked();
                boolean featDebugMode = binding.debugModeSwitch.isChecked();
                CustomerIOSDKConfig config = new CustomerIOSDKConfig(siteId,
                        apiKey,
                        trackingURL,
                        bqSecondsDelay,
                        bqMinTasks,
                        featTrackScreens,
                        featTrackDeviceAttributes,
                        featDebugMode);
                Disposable disposable = settingsViewModel
                        .updateConfigurations(config)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(preferences -> {
                            binding.progressIndicator.hide();
                            Toast.makeText(this, R.string.settings_save_msg, Toast.LENGTH_SHORT).show();
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
            parseLinkParams();
        });
    }

    private void updateIOWithConfig(@NonNull CustomerIOSDKConfig config) {
        ViewUtils.setTextWithSelectionIfFocused(binding.trackingUrlTextInput, config.getTrackingURL());
        ViewUtils.setTextWithSelectionIfFocused(binding.siteIdTextInput, config.getSiteId());
        ViewUtils.setTextWithSelectionIfFocused(binding.apiKeyTextInput, config.getApiKey());
        ViewUtils.setTextWithSelectionIfFocused(binding.bqDelayTextInput, StringUtils.fromDouble(config.getBackgroundQueueSecondsDelay()));
        ViewUtils.setTextWithSelectionIfFocused(binding.bqTasksTextInput, StringUtils.fromInteger(config.getBackgroundQueueMinNumOfTasks()));
        binding.trackScreensSwitch.setChecked(config.screenTrackingEnabled());
        binding.trackDeviceAttributesSwitch.setChecked(config.deviceAttributesTrackingEnabled());
        binding.debugModeSwitch.setChecked(config.debugModeEnabled());
    }
}
