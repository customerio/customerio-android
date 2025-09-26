package io.customer.android.sample.java_layout.ui.inline;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.customer.android.sample.java_layout.databinding.FragmentSingleInlineBinding;
import io.customer.android.sample.java_layout.ui.core.BaseFragment;

public class SingleInlineFragment extends BaseFragment<FragmentSingleInlineBinding> {
    
    private static final String TAG = "SingleInlineFragment";

    @NonNull
    @Override
    protected FragmentSingleInlineBinding inflateViewBinding() {
        return FragmentSingleInlineBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void setupContent() {
        // Set action listener for the inline in-app message
        binding.inappMessage.setActionListener(new InlineMessageActionListenerImpl(requireContext(), "Single Tab"));
    }

    public static SingleInlineFragment newInstance() {
        return new SingleInlineFragment();
    }
}
