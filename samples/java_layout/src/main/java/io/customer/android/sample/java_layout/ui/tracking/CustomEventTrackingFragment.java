package io.customer.android.sample.java_layout.ui.tracking;

import android.text.TextUtils;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.utils.ViewUtils;
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
            String eventName = ViewUtils.getTextTrimmed(binding.eventNameTextInput);
            String propertyName = ViewUtils.getTextTrimmed(binding.propertyNameTextInput);
            String propertyValue = ViewUtils.getTextTrimmed(binding.propertyValueTextInput);

            if (TextUtils.isEmpty(eventName)) {
                ViewUtils.setError(binding.eventNameInputLayout, getString(R.string.error_event_name));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.eventNameInputLayout, null);
            }
            if (TextUtils.isEmpty(propertyName)) {
                ViewUtils.setError(binding.propertyNameInputLayout, getString(R.string.error_property_name));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.propertyNameInputLayout, null);
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
