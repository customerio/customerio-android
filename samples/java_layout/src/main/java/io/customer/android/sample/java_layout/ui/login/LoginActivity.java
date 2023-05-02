package io.customer.android.sample.java_layout.ui.login;

import io.customer.android.sample.java_layout.databinding.ActivityLoginBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;

public class LoginActivity extends BaseActivity<ActivityLoginBinding> {

    @Override
    protected ActivityLoginBinding inflateViewBinding() {
        return ActivityLoginBinding.inflate(getLayoutInflater());
    }
}
