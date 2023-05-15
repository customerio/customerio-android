package io.customer.android.sample.java_layout.ui.common;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import io.customer.android.sample.java_layout.databinding.ActivitySimpleFragmentBinding;
import io.customer.android.sample.java_layout.ui.core.BaseActivity;
import io.customer.android.sample.java_layout.ui.dashboard.DashboardActivity;
import io.customer.android.sample.java_layout.ui.login.LoginActivity;
import io.customer.android.sample.java_layout.ui.tracking.AttributesTrackingFragment;
import io.customer.android.sample.java_layout.ui.tracking.CustomEventTrackingFragment;
import io.customer.android.sample.java_layout.ui.user.AuthViewModel;

public class SimpleFragmentActivity extends BaseActivity<ActivitySimpleFragmentBinding> {

    public static final String FRAGMENT_CUSTOM_TRACKING_EVENT = "FRAGMENT_CUSTOM_TRACKING_EVENT";
    public static final String FRAGMENT_DEVICE_ATTRIBUTES = "FRAGMENT_DEVICE_ATTRIBUTES";
    public static final String FRAGMENT_PROFILE_ATTRIBUTES = "FRAGMENT_PROFILE_ATTRIBUTES";

    private static final String ARG_FRAGMENT_NAME = "fragment_name";

    private AuthViewModel authViewModel;
    private String mFragmentName;

    public static Bundle getExtras(String fragmentName) {
        Bundle extras = new Bundle();
        extras.putString(ARG_FRAGMENT_NAME, fragmentName);
        return extras;
    }

    @Override
    protected ActivitySimpleFragmentBinding inflateViewBinding() {
        return ActivitySimpleFragmentBinding.inflate(getLayoutInflater());
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void injectDependencies() {
        authViewModel = viewModelProvider.get(AuthViewModel.class);
    }

    private void navigateUp() {
        // For better user experience, navigate to launcher activity on navigate up button
        if (isTaskRoot()) {
            startActivity(new Intent(SimpleFragmentActivity.this, DashboardActivity.class));
        }
        onBackPressed();
    }

    @Override
    protected void setupContent() {
        if (!parseFragmentParams()) {
            // Navigate up for unsupported deep links so app doesn't crash and user experience is
            // not affected
            navigateUp();
            return;
        }

        if (TextUtils.isEmpty(mFragmentName)) {
            throw new IllegalStateException("Fragment name cannot be null");
        }

        binding.topAppBar.setNavigationOnClickListener(view -> navigateUp());
        authViewModel.getUserLoggedInStateObservable().observe(this, isLoggedIn -> {
            if (isLoggedIn) {
                replaceFragment();
            } else {
                if (isTaskRoot()) {
                    startActivity(new Intent(SimpleFragmentActivity.this, LoginActivity.class));
                }
                finish();
            }
        });
    }

    private boolean parseFragmentParams() {
        Intent intent = getIntent();
        Uri deepLinkUrl = intent.getData();

        // deepLinkUrl contains URI if activity is launched from deep link or url from Customer.io push notification
        // e.g.
        // java-layout://events/custom
        // java-layout://attributes/profile
        if (deepLinkUrl != null) {
            String host = deepLinkUrl.getHost();
            String lastPathSegment = deepLinkUrl.getLastPathSegment();
            switch (host) {
                case "events":
                    if ("custom".equals(lastPathSegment)) {
                        mFragmentName = FRAGMENT_CUSTOM_TRACKING_EVENT;
                    }
                    break;
                case "attributes":
                    switch (lastPathSegment) {
                        case "device":
                            mFragmentName = FRAGMENT_DEVICE_ATTRIBUTES;
                            break;
                        case "profile":
                            mFragmentName = FRAGMENT_PROFILE_ATTRIBUTES;
                            break;
                    }
                    break;
            }
            // Return true if deep link was parsed successfully only, false otherwise for unsupported links
            return !TextUtils.isEmpty(mFragmentName);
        } else {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                mFragmentName = extras.getString(ARG_FRAGMENT_NAME);
            }
            // Return true if for argument provided from app code so we can force crash for incorrect values
            return true;
        }
    }

    private void replaceFragment() {
        final Fragment fragment;
        switch (mFragmentName) {
            case FRAGMENT_CUSTOM_TRACKING_EVENT:
                fragment = CustomEventTrackingFragment.newInstance();
                break;
            case FRAGMENT_DEVICE_ATTRIBUTES:
                fragment = AttributesTrackingFragment.newInstance(AttributesTrackingFragment.ATTRIBUTE_TYPE_DEVICE);
                break;
            case FRAGMENT_PROFILE_ATTRIBUTES:
                fragment = AttributesTrackingFragment.newInstance(AttributesTrackingFragment.ATTRIBUTE_TYPE_PROFILE);
                break;
            default:
                fragment = null;
        }

        if (fragment == null) {
            throw new IllegalArgumentException("Invalid fragment name provided");
        }

        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(binding.container.getId(), fragment);
        fragmentTransaction.commit();
        binding.progressIndicator.hide();
    }
}
