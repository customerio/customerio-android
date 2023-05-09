package io.customer.android.sample.java_layout.ui.tracking;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.databinding.FragmentCustomEventTrackingBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;
import io.customer.sdk.CustomerIO;

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

    @Override
    protected void setupContent() {
        binding.sendEventButton.setOnClickListener(view -> {
            String eventName = binding.eventNameTextInput.getText().toString().trim();
            Map<String, String> extras = new HashMap<>();
            extras.put(binding.propertyNameTextInput.getText().toString().trim(),
                    binding.propertyValueTextInput.getText().toString().trim());

            CustomerIO.instance().track(eventName, extras);
        });
    }
}
