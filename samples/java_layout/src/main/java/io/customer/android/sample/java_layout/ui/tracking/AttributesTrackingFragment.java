package io.customer.android.sample.java_layout.ui.tracking;

import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import java.util.HashMap;
import java.util.Map;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.FragmentAttributesTrackingBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;
import io.customer.sdk.CustomerIO;

public class AttributesTrackingFragment extends BaseFragment<FragmentAttributesTrackingBinding> {

    public static final String ATTRIBUTE_TYPE_DEVICE = "ATTRIBUTE_TYPE_DEVICE";
    public static final String ATTRIBUTE_TYPE_PROFILE = "ATTRIBUTE_TYPE_PROFILE";

    private static final String ARG_ATTRIBUTE_TYPE = "attribute_type";

    private String mAttributeType;

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
    protected void setupContent() {
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
            Map<String, String> attributes = new HashMap<>();
            attributes.put(binding.attributeNameTextInput.getText().toString().trim(),
                    binding.attributeValueTextInput.getText().toString().trim());

            final String attributeType;
            switch (mAttributeType) {
                case ATTRIBUTE_TYPE_DEVICE:
                    attributeType = getString(R.string.device);
                    CustomerIO.instance().setDeviceAttributes(attributes);
                    break;
                case ATTRIBUTE_TYPE_PROFILE:
                    attributeType = getString(R.string.profile);
                    CustomerIO.instance().setProfileAttributes(attributes);
                    break;
                default:
                    return;
            }

            FragmentActivity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity,
                        getString(R.string.attributes_tracked_msg_format, attributeType),
                        Toast.LENGTH_SHORT).show();
                activity.onBackPressed();
            }
        });
    }
}
