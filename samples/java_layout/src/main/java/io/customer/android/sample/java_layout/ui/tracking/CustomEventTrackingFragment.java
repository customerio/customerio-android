package io.customer.android.sample.java_layout.ui.tracking;

import io.customer.android.sample.java_layout.databinding.FragmentCustomEventTrackingBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;

public class CustomEventTrackingFragment extends BaseFragment<FragmentCustomEventTrackingBinding> {

    public CustomEventTrackingFragment() {
        // Required empty public constructor
    }

    public static CustomEventTrackingFragment newInstance() {
        return new CustomEventTrackingFragment();
    }

    @Override
    protected FragmentCustomEventTrackingBinding inflateViewBinding() {
        return FragmentCustomEventTrackingBinding.inflate(getLayoutInflater());
    }
}
