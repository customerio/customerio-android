package io.customer.android.sample.java_layout.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;

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
        binding.deviceTokenTextInput.setText("DeviceToken");
    }
}
