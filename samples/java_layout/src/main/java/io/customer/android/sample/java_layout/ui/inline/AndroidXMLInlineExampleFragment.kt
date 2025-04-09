package io.customer.android.sample.java_layout.ui.inline

import io.customer.android.sample.java_layout.databinding.FragmentAndroidXmlInlineExampleBinding
import io.customer.android.sample.java_layout.ui.core.BaseFragment

class AndroidXMLInlineExampleFragment : BaseFragment<FragmentAndroidXmlInlineExampleBinding>() {
    override fun inflateViewBinding(): FragmentAndroidXmlInlineExampleBinding {
        return FragmentAndroidXmlInlineExampleBinding.inflate(layoutInflater)
    }

    companion object {
        @JvmStatic
        fun newInstance() = AndroidXMLInlineExampleFragment()
    }
}
