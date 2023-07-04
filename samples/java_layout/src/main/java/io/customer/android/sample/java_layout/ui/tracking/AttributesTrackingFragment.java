package io.customer.android.sample.java_layout.ui.tracking;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import com.google.android.material.snackbar.Snackbar;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.FragmentAttributesTrackingBinding;
import io.customer.android.sample.java_layout.sdk.CustomerIORepository;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;
import io.customer.android.sample.java_layout.utils.ViewUtils;

public class AttributesTrackingFragment extends BaseFragment<FragmentAttributesTrackingBinding> {

    public static final String ATTRIBUTE_TYPE_DEVICE = "ATTRIBUTE_TYPE_DEVICE";
    public static final String ATTRIBUTE_TYPE_PROFILE = "ATTRIBUTE_TYPE_PROFILE";

    private static final String ARG_ATTRIBUTE_TYPE = "attribute_type";

    private String mAttributeType;

    private CustomerIORepository customerIORepository;

    public AttributesTrackingFragment() {
        // Required empty public constructor
    }

    public static AttributesTrackingFragment newInstance(String attributeType) {
        AttributesTrackingFragment fragment = new AttributesTrackingFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ATTRIBUTE_TYPE, attributeType);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle arguments = getArguments();
        if (arguments != null) {
            mAttributeType = arguments.getString(ARG_ATTRIBUTE_TYPE);
        }
    }

    @Override
    protected FragmentAttributesTrackingBinding inflateViewBinding() {
        return FragmentAttributesTrackingBinding.inflate(getLayoutInflater());
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
        ViewUtils.prepareForAutomatedTests(binding.attributeNameTextInput, R.string.acd_attribute_name_input);
        ViewUtils.prepareForAutomatedTests(binding.attributeValueTextInput, R.string.acd_attribute_value_input);
        switch (mAttributeType) {
            case ATTRIBUTE_TYPE_DEVICE:
                ViewUtils.prepareForAutomatedTests(binding.sendEventButton, R.string.acd_send_device_attribute_button);
                break;
            case ATTRIBUTE_TYPE_PROFILE:
                ViewUtils.prepareForAutomatedTests(binding.sendEventButton, R.string.acd_send_profile_attribute_button);
                break;
        }
    }

    private void setupViews() {
        switch (mAttributeType) {
            case ATTRIBUTE_TYPE_DEVICE:
                binding.screenTitleTextView.setText(R.string.screen_title_device_attributes);
                binding.sendEventButton.setText(R.string.send_device_attribute);
                break;
            case ATTRIBUTE_TYPE_PROFILE:
                binding.screenTitleTextView.setText(R.string.screen_title_profile_attributes);
                binding.sendEventButton.setText(R.string.send_profile_attribute);
                break;
        }

        binding.sendEventButton.setOnClickListener(view -> {
            String attributeName = ViewUtils.getText(binding.attributeNameTextInput);
            String attributeValue = ViewUtils.getText(binding.attributeValueTextInput);

            Map<String, String> attributes = new HashMap<>();
            attributes.put(attributeName, attributeValue);

            final String attributeType;
            switch (mAttributeType) {
                case ATTRIBUTE_TYPE_DEVICE:
                    attributeType = getString(R.string.device);
                    customerIORepository.setDeviceAttributes(attributes);
                    break;
                case ATTRIBUTE_TYPE_PROFILE:
                    attributeType = getString(R.string.profile);
                    customerIORepository.setProfileAttributes(attributes);
                    break;
                default:
                    return;
            }

            FragmentActivity activity = getActivity();
            if (activity != null) {
                Snackbar.make(binding.sendEventButton,
                        getString(R.string.attributes_tracked_msg_format, attributeType),
                        Snackbar.LENGTH_SHORT).show();
            }
        });
    }
}
