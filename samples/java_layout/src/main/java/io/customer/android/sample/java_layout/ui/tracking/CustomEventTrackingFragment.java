package io.customer.android.sample.java_layout.ui.tracking;

import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.FragmentCustomEventTrackingBinding;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;
import io.customer.android.sample.java_layout.utils.ViewUtils;

public class CustomEventTrackingFragment extends BaseFragment<FragmentCustomEventTrackingBinding> {

    private CustomerIORepository customerIORepository;

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
    protected void injectDependencies() {
        customerIORepository = applicationGraph.getCustomerIORepository();
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

            if (isFormValid) {
                Map<String, String> extras = new HashMap<>();
                extras.put(propertyName, propertyValue);
                customerIORepository.trackEvent(eventName, extras);

                FragmentActivity activity = getActivity();
                if (activity != null) {
                    MaterialAlertDialogBuilder builder = ViewUtils.createAlertDialog(activity);
                    builder.setMessage(R.string.event_tracked_msg);
                    builder.setOnDismissListener(dialogInterface -> activity.onBackPressed());
                    builder.show();
                }
            }
        });
    }
}
