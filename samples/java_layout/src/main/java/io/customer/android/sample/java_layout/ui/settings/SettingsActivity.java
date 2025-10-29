package io.customer.android.sample.java_layout.ui.settings;

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
import io.customer.android.sample.java_layout.utils.ViewUtils;
import io.customer.datapipelines.config.ScreenView;
import io.customer.messaginginapp.ModuleMessagingInApp;
import io.customer.sdk.core.util.CioLogLevel;
import io.customer.sdk.data.model.Region;
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
        prepareViews();
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
                ViewUtils.setTextWithSelectionIfFocused(binding.settingsCdpApiKeyLabel, cdpApiKey);
            }
            String siteId = deepLinkUri.getQueryParameter("site_id");
            if (siteId != null) {
                ViewUtils.setTextWithSelectionIfFocused(binding.settingsSiteIdKeyLabel, siteId);
            }
            String sessionId = deepLinkUri.getQueryParameter("cioSessionId");
            ModuleMessagingInApp.instance().setupPreviewMode(sessionId);
        }
        isLinkParamsPopulated = true;
    }

    private void prepareViews() {
        binding.settingsScreenViewUseAllButton.setText(ScreenView.All.INSTANCE.getName());
        binding.settingsScreenViewUseInAppButton.setText(ScreenView.InApp.INSTANCE.getName());
    }

    private void prepareViewsForAutomatedTests() {
        ViewUtils.prepareForAutomatedTests(binding.topAppBar);
        ViewUtils.prepareForAutomatedTests(binding.settingsCdpApiKeyLabel, R.string.acd_cdp_api_key_input);
        ViewUtils.prepareForAutomatedTests(binding.settingsSiteIdKeyLabel, R.string.acd_site_id_input);
        ViewUtils.prepareForAutomatedTests(binding.settingsSaveButton, R.string.acd_save_settings_button);
        ViewUtils.prepareForAutomatedTests(binding.settingsRestoreDefaultsButton, R.string.acd_restore_default_settings_button);
    }

    private void setupViews() {
        binding.topAppBar.setNavigationOnClickListener(view -> {
            // For better user experience, navigate to launcher activity on navigate up button
            if (isTaskRoot()) {
                startActivity(new Intent(SettingsActivity.this, DashboardActivity.class));
            }
            onBackPressed();
        });
        binding.settingsSaveButton.setOnClickListener(view -> saveSettings());
        binding.settingsRestoreDefaultsButton.setOnClickListener(view -> updateIOWithConfig(CustomerIOSDKConfig.getDefaultConfigurations()));
        ViewUtils.clearErrorWhenTextedEntered(binding.settingsCdpApiKeyLabel, binding.settingsCdpApiKeyLayout);
        ViewUtils.clearErrorWhenTextedEntered(binding.settingsSiteIdKeyLabel, binding.settingsSiteIdKeyLayout);
    }

    private void setupObservers() {
        settingsViewModel.getSDKConfigObservable().observe(this, config -> {
            binding.progressIndicator.hide();
            updateIOWithConfig(config);
            parseLinkParams();
        });
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private <T extends Number & Comparable<T>> boolean isNumberValid(T number, T min) {
        // Compares if the value is not null and greater than or equal to min
        // i.e. evaluates number >= min
        return number != null && number.compareTo(min) >= 0;
    }

    private void updateIOWithConfig(@NonNull CustomerIOSDKConfig config) {
        ViewUtils.setTextWithSelectionIfFocused(binding.settingsCdpApiKeyLabel, config.getCdpApiKey());
        ViewUtils.setTextWithSelectionIfFocused(binding.settingsSiteIdKeyLabel, config.getSiteId());
        binding.settingsRegionValuesGroup.check(getCheckedRegionButtonId(config.getRegion()));
        binding.settingsTrackDeviceAttrsValuesGroup.check(getCheckedAutoTrackDeviceAttributesButtonId(config.isDeviceAttributesTrackingEnabled()));
        binding.settingsTrackScreenViewsValuesGroup.check(getCheckedTrackScreenViewsButtonId(config.isScreenTrackingEnabled()));
        binding.settingsTrackAppLifecycleValuesGroup.check(getCheckedTrackAppLifecycleButtonId(config.isApplicationLifecycleTrackingEnabled()));
        binding.screenViewUseSettingsValuesGroup.check(getCheckedScreenViewUseButtonId(config.getScreenViewUse()));
        binding.settingsLogLevelValuesGroup.check(getCheckedLogLevelButtonId(config.getLogLevel()));
        binding.settingsTestModeValuesGroup.check(getCheckedTestModeButtonId(config.isTestModeEnabled()));
        binding.settingsInAppMessagingValuesGroup.check(getCheckedInAppMessagingButtonId(config.isInAppMessagingEnabled()));
    }

    private void saveSettings() {
        CustomerIOSDKConfig currentSettings = settingsViewModel.getSDKConfigObservable().getValue();
        if (currentSettings == null) {
            Toast.makeText(this, "Error! Cannot save settings!", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isFormValid;

        String cdpApiKey = ViewUtils.getTextTrimmed(binding.settingsCdpApiKeyLabel);
        isFormValid = updateErrorState(binding.settingsCdpApiKeyLayout, TextUtils.isEmpty(cdpApiKey), R.string.error_text_input_field_blank);

        String siteId = ViewUtils.getTextTrimmed(binding.settingsSiteIdKeyLabel);
        isFormValid = updateErrorState(binding.settingsSiteIdKeyLayout, TextUtils.isEmpty(siteId), R.string.error_text_input_field_blank) && isFormValid;

        if (isFormValid) {
            binding.progressIndicator.show();
            CustomerIOSDKConfig config = createNewSettings(cdpApiKey, siteId, currentSettings);
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

    @NonNull
    private CustomerIOSDKConfig createNewSettings(String cdpApiKey, String siteId, CustomerIOSDKConfig currentSettings) {
        boolean featTrackScreens = binding.settingsTrackScreenViewsValuesGroup.getCheckedButtonId() == R.id.settings_track_screen_views_yes_button;
        boolean featTrackDeviceAttributes = binding.settingsTrackDeviceAttrsValuesGroup.getCheckedButtonId() == R.id.settings_track_device_attrs_yes_button;
        boolean featTrackApplicationLifecycle = binding.settingsTrackAppLifecycleValuesGroup.getCheckedButtonId() == R.id.settings_track_app_lifecycle_yes_button;
        boolean featTestModeEnabled = binding.settingsTestModeValuesGroup.getCheckedButtonId() == R.id.settings_test_mode_yes_button;
        boolean featInAppMessagingEnabled = binding.settingsInAppMessagingValuesGroup.getCheckedButtonId() == R.id.settings_in_app_messaging_yes_button;
        CioLogLevel logLevel = getSelectedLogLevel();
        Region region = getSelectedRegion();
        ScreenView screenViewUse = getSelectedScreenViewUse();

        return new CustomerIOSDKConfig(cdpApiKey,
                siteId,
                currentSettings.getApiHost(),
                currentSettings.getCdnHost(),
                featTrackScreens,
                featTrackDeviceAttributes,
                logLevel,
                region,
                screenViewUse,
                featTrackApplicationLifecycle,
                featTestModeEnabled,
                featInAppMessagingEnabled);
    }

    @NonNull
    private CioLogLevel getSelectedLogLevel() {
        int checkedButton = binding.settingsLogLevelValuesGroup.getCheckedButtonId();
        if (checkedButton == R.id.settings_log_level_none_button) {
            return CioLogLevel.NONE;
        } else if (checkedButton == R.id.settings_log_level_error_button) {
            return CioLogLevel.ERROR;
        } else if (checkedButton == R.id.settings_log_level_info_button) {
            return CioLogLevel.INFO;
        } else if (checkedButton == R.id.settings_log_level_debug_button) {
            return CioLogLevel.DEBUG;
        }
        throw new IllegalStateException();
    }

    @NonNull
    private Region getSelectedRegion() {
        int checkedButton = binding.settingsRegionValuesGroup.getCheckedButtonId();
        if (checkedButton == R.id.settings_region_us_button) {
            return Region.US.INSTANCE;
        } else if (checkedButton == R.id.settings_region_eu_button) {
            return Region.EU.INSTANCE;
        }
        throw new IllegalStateException();
    }


    @NonNull
    private ScreenView getSelectedScreenViewUse() {
        int checkedButton = binding.screenViewUseSettingsValuesGroup.getCheckedButtonId();
        if (checkedButton == R.id.settings_screen_view_use_all_button) {
            return ScreenView.All.INSTANCE;
        } else if (checkedButton == R.id.settings_screen_view_use_in_app_button) {
            return ScreenView.InApp.INSTANCE;
        }
        throw new IllegalStateException();
    }

    private int getCheckedInAppMessagingButtonId(boolean enabled) {
        return enabled ? R.id.settings_in_app_messaging_yes_button : R.id.settings_in_app_messaging_no_button;
    }

    private int getCheckedTestModeButtonId(boolean enabled) {
        return enabled ? R.id.settings_test_mode_yes_button : R.id.settings_test_mode_no_button;
    }

    private int getCheckedLogLevelButtonId(@NonNull CioLogLevel logLevel) {
        switch (logLevel) {
            case NONE:
                return R.id.settings_log_level_none_button;
            case ERROR:
                return R.id.settings_log_level_error_button;
            case INFO:
                return R.id.settings_log_level_info_button;
            case DEBUG:
                return R.id.settings_log_level_debug_button;
            default:
                throw new IllegalStateException();
        }
    }

    private int getCheckedTrackAppLifecycleButtonId(boolean enabled) {
        return enabled ? R.id.settings_track_app_lifecycle_yes_button
                : R.id.settings_track_app_lifecycle_no_button;
    }

    private int getCheckedTrackScreenViewsButtonId(boolean enabled) {
        return enabled ? R.id.settings_track_screen_views_yes_button
                : R.id.settings_track_screen_views_no_button;
    }

    private int getCheckedAutoTrackDeviceAttributesButtonId(boolean enabled) {
        return enabled ? R.id.settings_track_device_attrs_yes_button
                : R.id.settings_track_device_attrs_no_button;
    }

    private int getCheckedRegionButtonId(@NonNull Region region) {
        return region instanceof Region.US ? R.id.settings_region_us_button
                : R.id.settings_region_eu_button;
    }

    private int getCheckedScreenViewUseButtonId(@NonNull ScreenView screenViewUse) {
        if (screenViewUse instanceof ScreenView.InApp) {
            return R.id.settings_screen_view_use_in_app_button;
        }
        return R.id.settings_screen_view_use_all_button;
    }

    private boolean updateErrorState(TextInputLayout textInputLayout,
                                     boolean isErrorEnabled,
                                     @StringRes int errorResId) {
        String error = isErrorEnabled ? getString(errorResId) : null;
        ViewUtils.setError(textInputLayout, error);
        return !isErrorEnabled;
    }
}
