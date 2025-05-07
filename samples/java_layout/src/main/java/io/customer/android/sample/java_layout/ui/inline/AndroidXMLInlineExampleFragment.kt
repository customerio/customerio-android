package io.customer.android.sample.java_layout.ui.inline

import io.customer.android.sample.java_layout.databinding.FragmentAndroidXmlInlineExampleBinding
import io.customer.android.sample.java_layout.ui.core.BaseFragment

class AndroidXMLInlineExampleFragment : BaseFragment<FragmentAndroidXmlInlineExampleBinding>() {

    override fun inflateViewBinding(): FragmentAndroidXmlInlineExampleBinding {
        return FragmentAndroidXmlInlineExampleBinding.inflate(layoutInflater)
    }

    override fun setupContent() {
        // Set element ID for sticky header in-app message using Kotlin property
        // to validate the functionality of setting element ID from Kotlin code
        binding.stickyHeaderInAppMessage.elementId = "sticky-header"

        // Set action listener for the inline in-app message
        binding.inlineInAppMessage.setInlineMessageActionListener(InlineMessageActionListenerImpl(requireContext(), "Inline"))

        // Set action listener for the below fold in-app message
        binding.belowFoldInAppMessage.setInlineMessageActionListener(InlineMessageActionListenerImpl(requireContext(), "Below Fold"))
    }

    companion object {
        @JvmStatic
        fun newInstance() = AndroidXMLInlineExampleFragment()
    }
}
