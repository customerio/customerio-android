package io.customer.android.sample.java_layout.ui.inline;

import androidx.annotation.NonNull;

import io.customer.android.sample.java_layout.databinding.FragmentMultipleInlineBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;

public class MultipleInlineFragment extends BaseFragment<FragmentMultipleInlineBinding> {
    
    private static final String TAG = "MultipleInlineFragment";

    @NonNull
    @Override
    protected FragmentMultipleInlineBinding inflateViewBinding() {
        return FragmentMultipleInlineBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        // Set action listeners for all inline in-app messages
        binding.firstInappMessage.setActionListener(new InlineMessageActionListenerImpl(requireContext(), "Multi Top"));
        binding.secondInappMessage.setActionListener(new InlineMessageActionListenerImpl(requireContext(), "Multi Center"));
        binding.thirdInappMessage.setActionListener(new InlineMessageActionListenerImpl(requireContext(), "Multi Bottom"));
    }

    public static MultipleInlineFragment newInstance() {
        return new MultipleInlineFragment();
    }
}
