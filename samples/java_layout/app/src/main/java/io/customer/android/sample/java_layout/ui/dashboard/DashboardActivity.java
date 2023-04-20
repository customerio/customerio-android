package io.customer.android.sample.java_layout.ui.dashboard;

import io.customer.android.sample.java_layout.databinding.ActivityDashboardBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;

public class DashboardActivity extends BaseActivity<ActivityDashboardBinding> {

    @Override
    protected ActivityDashboardBinding inflateViewBinding() {
        return ActivityDashboardBinding.inflate(getLayoutInflater());
    }
}
