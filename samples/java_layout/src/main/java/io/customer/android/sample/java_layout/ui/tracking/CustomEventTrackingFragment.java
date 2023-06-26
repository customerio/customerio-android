package io.customer.android.sample.java_layout.ui.tracking;

import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.snackbar.Snackbar;

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
        prepareViewsForAutomatedTests();
        setupViews();
    }

    private void prepareViewsForAutomatedTests() {
        ViewUtils.prepareForAutomatedTests(binding.eventNameTextInput, R.string.acd_event_name_input);
        ViewUtils.prepareForAutomatedTests(binding.propertyNameTextInput, R.string.acd_property_name_input);
        ViewUtils.prepareForAutomatedTests(binding.propertyValueTextInput, R.string.acd_property_value_input);
        ViewUtils.prepareForAutomatedTests(binding.sendEventButton, R.string.acd_send_event_button);
    }

    private void setupViews() {
        binding.sendEventButton.setOnClickListener(view -> {
            boolean isFormValid = true;
            String eventName = ViewUtils.getText(binding.eventNameTextInput);
            String propertyName = ViewUtils.getText(binding.propertyNameTextInput);
            String propertyValue = ViewUtils.getText(binding.propertyValueTextInput);

            if (TextUtils.isEmpty(eventName)) {
                ViewUtils.setError(binding.eventNameInputLayout, getString(R.string.error_text_input_field_empty));
                isFormValid = false;
            } else {
                ViewUtils.setError(binding.eventNameInputLayout, null);
            }

            if (isFormValid) {
                Map<String, String> extras = new HashMap<>();
                if (!TextUtils.isEmpty(propertyName)) {
                    extras.put(propertyName, propertyValue);
                }
                customerIORepository.trackEvent(eventName, extras);

                FragmentActivity activity = getActivity();
                if (activity != null) {
                    Snackbar.make(binding.sendEventButton,
                            R.string.event_tracked_msg,
                            Snackbar.LENGTH_SHORT).show();
                }
            }
        });
    }
}
