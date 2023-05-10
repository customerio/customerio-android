package io.customer.android.sample.java_layout.ui.tracking;

import android.text.TextUtils;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
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
            boolean isFormValid = true;
            String eventName = binding.eventNameTextInput.getText().toString().trim();
            String propertyName = binding.propertyNameTextInput.getText().toString().trim();
            String propertyValue = binding.propertyValueTextInput.getText().toString().trim();

            if (TextUtils.isEmpty(eventName)) {
                binding.eventNameInputLayout.setErrorEnabled(true);
                binding.eventNameInputLayout.setError(getString(R.string.error_event_name));
                isFormValid = false;
            } else {
                binding.eventNameInputLayout.setErrorEnabled(false);
                binding.eventNameInputLayout.setError(null);
            }
            if (TextUtils.isEmpty(propertyName)) {
                binding.propertyNameInputLayout.setErrorEnabled(true);
                binding.propertyNameInputLayout.setError(getString(R.string.error_property_name));
                isFormValid = false;
            } else {
                binding.propertyNameInputLayout.setErrorEnabled(false);
                binding.propertyNameInputLayout.setError(null);
            }

            if (isFormValid) {
                Map<String, String> extras = new HashMap<>();
                extras.put(propertyName, propertyValue);
                CustomerIO.instance().track(eventName, extras);

                FragmentActivity activity = getActivity();
                if (activity != null) {
                    Toast.makeText(activity,
                            getString(R.string.event_tracked_msg_format, eventName),
                            Toast.LENGTH_SHORT).show();
                    activity.onBackPressed();
                }
            }
        });
    }
}
