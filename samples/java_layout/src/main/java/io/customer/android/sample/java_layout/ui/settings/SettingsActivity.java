package io.customer.android.sample.java_layout.ui.settings;

import io.customer.android.sample.java_layout.databinding.ActivitySettingsBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;

public class SettingsActivity extends BaseActivity<ActivitySettingsBinding> {

    @Override
    protected ActivitySettingsBinding inflateViewBinding() {
        return ActivitySettingsBinding.inflate(getLayoutInflater());
    }
}
