package io.customer.android.sample.java_layout.ui.inline

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.databinding.ActivitySimpleFragmentBinding
import io.customer.android.sample.java_layout.ui.core.BaseFragmentContainerActivity
import io.customer.android.sample.java_layout.ui.user.AuthViewModel
import io.customer.sdk.tracking.TrackableScreen

class InlineExamplesActivity :
    BaseFragmentContainerActivity<ActivitySimpleFragmentBinding>(),
    TrackableScreen {
    private lateinit var authViewModel: AuthViewModel

    override val fragmentContainer: View get() = binding.container
    override val progressIndicator: LinearProgressIndicator get() = binding.progressIndicator
    override fun getFragmentTitle(): String = getScreenName()

    override fun getScreenName(): String = when (fragmentName) {
        FRAGMENT_ANDROID_XML -> getString(R.string.inline_examples_xml_layout)
        else -> getString(R.string.label_inline_examples_activity)
    }

    override fun inflateViewBinding(): ActivitySimpleFragmentBinding {
        return ActivitySimpleFragmentBinding.inflate(layoutInflater)
    }

    override fun injectDependencies() {
        authViewModel = viewModelProvider[AuthViewModel::class.java]
    }

    override fun setupContent() {
        setupToolbar(binding.topAppBar, useAsSupportActionBar = true)
        setupWithAuthViewModel(authViewModel)
    }

    override fun findFragmentByName(fragmentName: String): Fragment? = when (fragmentName) {
        FRAGMENT_ANDROID_XML -> AndroidXMLInlineExampleFragment.newInstance()
        else -> null
    }

    companion object {
        const val FRAGMENT_ANDROID_XML: String = "FRAGMENT_ANDROID_XML"

        @JvmStatic
        fun getExtras(fragmentName: String?): Bundle {
            return BaseFragmentContainerActivity.getExtras(fragmentName)
        }
    }
}
