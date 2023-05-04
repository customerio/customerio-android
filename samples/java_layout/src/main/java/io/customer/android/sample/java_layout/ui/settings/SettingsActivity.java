package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.messagingpush.provider.FCMTokenProviderImpl;
import io.customer.sdk.CustomerIOShared;
import io.customer.sdk.device.DeviceTokenProvider;
import io.customer.sdk.util.Logger;

public class SettingsActivity extends BaseActivity<ActivitySettingsBinding> {

    @Override
    protected ActivitySettingsBinding inflateViewBinding() {
        return ActivitySettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        setupViews();
        setupObservers();
    }

    private void setupViews() {
        binding.topAppBar.setNavigationOnClickListener(view -> {
            finish();
        });
        binding.deviceTokenInputLayout.setEndIconOnClickListener(view -> {
            //noinspection ConstantConditions
            String deviceToken = binding.deviceTokenTextInput.getText().toString().trim();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.device_token), deviceToken);
            clipboard.setPrimaryClip(clip);
        });
    }

    private void setupObservers() {
        // TODO: Expose SDK method to get device token and replace here
        Logger cioSdkLogger = CustomerIOShared.instance().getDiStaticGraph().getLogger();
        DeviceTokenProvider fcmTokenProvider = new FCMTokenProviderImpl(cioSdkLogger, this);
        fcmTokenProvider.getCurrentToken(token -> {
            binding.deviceTokenTextInput.setText(token);
            return null;
        });
    }
}
