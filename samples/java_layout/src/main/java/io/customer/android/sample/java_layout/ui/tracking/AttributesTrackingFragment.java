package io.customer.android.sample.java_layout.ui.tracking;

import android.os.Bundle;

import io.customer.android.sample.java_layout.R;
import io.customer.android.sample.java_layout.databinding.FragmentAttributesTrackingBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;

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
    }
}
