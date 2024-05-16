package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.textfield.TextInputLayout;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.data.model.CustomerIOSDKConfig;
import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.utils.OSUtils;
import io.customer.android.sample.java_layout.utils.StringUtils;
import io.customer.android.sample.java_layout.utils.ViewUtils;
import io.customer.sdk.CustomerIO;
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
        prepareViewsForAutomatedTests();
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
        // java-sample://settings?cdp_api_key=xxx&site_id=yyy
        // https://www.java-sample.com/settings?cdp_api_key=xxx&site_id=yyy

        if (deepLinkUri != null) {
            String cdpApiKey = deepLinkUri.getQueryParameter("cdp_api_key");
            if (cdpApiKey != null) {
                ViewUtils.setTextWithSelectionIfFocused(binding.cdpApiKeyTextInput, cdpApiKey);
            }
            String siteId = deepLinkUri.getQueryParameter("site_id");
            if (siteId != null) {
                ViewUtils.setTextWithSelectionIfFocused(binding.siteIdTextInput, siteId);
            }
        }
        isLinkParamsPopulated = true;
    }

    private void prepareViewsForAutomatedTests() {
        ViewUtils.prepareForAutomatedTests(binding.topAppBar);
        ViewUtils.prepareForAutomatedTests(binding.deviceTokenTextInput, R.string.acd_device_token_input);
        ViewUtils.prepareForAutomatedTests(binding.apiHostTextInput, R.string.acd_api_host_input);
        ViewUtils.prepareForAutomatedTests(binding.cdnHostTextInput, R.string.acd_cdn_host_input);
        ViewUtils.prepareForAutomatedTests(binding.cdpApiKeyTextInput, R.string.acd_cdp_api_key_input);
        ViewUtils.prepareForAutomatedTests(binding.siteIdTextInput, R.string.acd_site_id_input);
        ViewUtils.prepareForAutomatedTests(binding.flushIntervalTextInput, R.string.acd_flush_interval_input);
        ViewUtils.prepareForAutomatedTests(binding.flushAtTextInput, R.string.acd_flush_at_input);
        ViewUtils.prepareForAutomatedTests(binding.trackScreensSwitch, R.string.acd_track_screens_switch);
        ViewUtils.prepareForAutomatedTests(binding.trackDeviceAttributesSwitch, R.string.acd_track_device_attributes_switch);
        ViewUtils.prepareForAutomatedTests(binding.debugModeSwitch, R.string.acd_debug_mode_switch);
        ViewUtils.prepareForAutomatedTests(binding.saveButton, R.string.acd_save_settings_button);
        ViewUtils.prepareForAutomatedTests(binding.restoreDefaultsButton, R.string.acd_restore_default_settings_button);
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
            Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show();
        });
        binding.saveButton.setOnClickListener(view -> saveSettings());
        binding.restoreDefaultsButton.setOnClickListener(view -> {
            updateIOWithConfig(CustomerIOSDKConfig.getDefaultConfigurations());
            saveSettings();
        });
    }

    private void setupObservers() {

        binding.deviceTokenTextInput.setText(CustomerIO.instance().getRegisteredDeviceToken());

        settingsViewModel.getSDKConfigObservable().observe(this, config -> {
            binding.progressIndicator.hide();
            updateIOWithConfig(config);
            parseLinkParams();
        });
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

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private <T extends Number & Comparable<T>> boolean isNumberValid(T number, T min) {
        // Compares if the value is not null and greater than or equal to min
        // i.e. evaluates number >= min
        return number != null && number.compareTo(min) >= 0;
    }

    private void updateIOWithConfig(@NonNull CustomerIOSDKConfig config) {
        ViewUtils.setTextWithSelectionIfFocused(binding.apiHostTextInput, config.getApiHost());
        ViewUtils.setTextWithSelectionIfFocused(binding.cdnHostTextInput, config.getCdnHost());
        ViewUtils.setTextWithSelectionIfFocused(binding.cdpApiKeyTextInput, config.getCdpApiKey());
        ViewUtils.setTextWithSelectionIfFocused(binding.siteIdTextInput, config.getSiteId());
        ViewUtils.setTextWithSelectionIfFocused(binding.flushIntervalTextInput, StringUtils.fromInteger(config.getFlushInterval()));
        ViewUtils.setTextWithSelectionIfFocused(binding.flushAtTextInput, StringUtils.fromInteger(config.getFlushAt()));
        binding.trackScreensSwitch.setChecked(config.screenTrackingEnabled());
        binding.trackDeviceAttributesSwitch.setChecked(config.deviceAttributesTrackingEnabled());
        binding.debugModeSwitch.setChecked(config.debugModeEnabled());
    }

    private void saveSettings() {
        boolean isFormValid;

        String apiHost = ViewUtils.getTextTrimmed(binding.apiHostTextInput);
        isFormValid = updateErrorState(binding.apiHostInputLayout, isHostURLInvalid(apiHost), R.string.error_host_url);

        String cdnHost = ViewUtils.getTextTrimmed(binding.cdnHostTextInput);
        isFormValid = updateErrorState(binding.cdnHostInputLayout, isHostURLInvalid(cdnHost), R.string.error_host_url) && isFormValid;

        String cdpApiKey = ViewUtils.getTextTrimmed(binding.cdpApiKeyTextInput);
        isFormValid = updateErrorState(binding.cdpApiKeyInputLayout, TextUtils.isEmpty(cdpApiKey), R.string.error_text_input_field_blank) && isFormValid;

        String siteId = ViewUtils.getTextTrimmed(binding.siteIdTextInput);
        isFormValid = updateErrorState(binding.siteIdInputLayout, TextUtils.isEmpty(siteId), R.string.error_text_input_field_blank) && isFormValid;

        String flushIntervalText = ViewUtils.getTextTrimmed(binding.flushIntervalTextInput);
        Integer flushInterval = StringUtils.parseInteger(flushIntervalText, null);
        boolean isFlushIntervalTextEmpty = TextUtils.isEmpty(flushIntervalText);
        if (isFlushIntervalTextEmpty) {
            isFormValid = updateErrorState(binding.flushIntervalInputLayout, true, R.string.error_text_input_field_blank) && isFormValid;
        } else {
            int minDelay = 1;
            isFormValid = updateErrorState(binding.flushIntervalInputLayout,
                    !isNumberValid(flushInterval, minDelay),
                    getString(R.string.error_number_input_field_small, String.valueOf(minDelay))) && isFormValid;
        }

        String flushAtText = ViewUtils.getTextTrimmed(binding.flushAtTextInput);
        Integer flushAt = StringUtils.parseInteger(flushAtText, null);
        boolean isFlushAtTextEmpty = TextUtils.isEmpty(flushAtText);
        if (isFlushAtTextEmpty) {
            isFormValid = updateErrorState(binding.flushAtInputLayout, true, R.string.error_text_input_field_blank) && isFormValid;
        } else {
            int minTasks = 1;
            isFormValid = updateErrorState(binding.flushAtInputLayout,
                    !isNumberValid(flushAt, minTasks),
                    getString(R.string.error_number_input_field_small, String.valueOf(minTasks))) && isFormValid;
        }

        if (isFormValid) {
            binding.progressIndicator.show();
            boolean featTrackScreens = binding.trackScreensSwitch.isChecked();
            boolean featTrackDeviceAttributes = binding.trackDeviceAttributesSwitch.isChecked();
            boolean featDebugMode = binding.debugModeSwitch.isChecked();
            CustomerIOSDKConfig config = new CustomerIOSDKConfig(cdpApiKey,
                    siteId,
                    apiHost,
                    cdnHost,
                    flushInterval,
                    flushAt,
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
    }

    private boolean updateErrorState(TextInputLayout textInputLayout,
                                     boolean isErrorEnabled,
                                     @StringRes int errorResId) {
        String error = isErrorEnabled ? getString(errorResId) : null;
        ViewUtils.setError(textInputLayout, error);
        return !isErrorEnabled;
    }

    private boolean updateErrorState(TextInputLayout textInputLayout,
                                     boolean isErrorEnabled,
                                     String errorMessage) {
        String error = isErrorEnabled ? errorMessage : null;
        ViewUtils.setError(textInputLayout, error);
        return !isErrorEnabled;
    }
}
