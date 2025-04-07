package io.customer.android.sample.java_layout.ui.tracking

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.customer.android.sample.java_layout.databinding.ActivitySimpleFragmentBinding
import io.customer.android.sample.java_layout.ui.core.BaseFragmentContainerActivity
import io.customer.android.sample.java_layout.ui.user.AuthViewModel

class TrackingFragmentActivity : BaseFragmentContainerActivity<ActivitySimpleFragmentBinding>() {
    private lateinit var authViewModel: AuthViewModel

    override val fragmentContainer: View get() = binding.container
    override val progressIndicator: LinearProgressIndicator get() = binding.progressIndicator

    override fun inflateViewBinding(): ActivitySimpleFragmentBinding {
        return ActivitySimpleFragmentBinding.inflate(layoutInflater)
    }

    override fun injectDependencies() {
        authViewModel = viewModelProvider[AuthViewModel::class.java]
    }

    override fun setupContent() {
        setupToolbar(binding.topAppBar)
        setupWithAuthViewModel(authViewModel)
    }

    override fun findFragmentByName(fragmentName: String): Fragment? = when (fragmentName) {
        FRAGMENT_CUSTOM_TRACKING_EVENT -> CustomEventTrackingFragment.newInstance()
        FRAGMENT_DEVICE_ATTRIBUTES -> AttributesTrackingFragment.newInstance(AttributesTrackingFragment.ATTRIBUTE_TYPE_DEVICE)
        FRAGMENT_PROFILE_ATTRIBUTES -> AttributesTrackingFragment.newInstance(AttributesTrackingFragment.ATTRIBUTE_TYPE_PROFILE)
        else -> null
    }

    companion object {
        const val FRAGMENT_CUSTOM_TRACKING_EVENT: String = "FRAGMENT_CUSTOM_TRACKING_EVENT"
        const val FRAGMENT_DEVICE_ATTRIBUTES: String = "FRAGMENT_DEVICE_ATTRIBUTES"
        const val FRAGMENT_PROFILE_ATTRIBUTES: String = "FRAGMENT_PROFILE_ATTRIBUTES"

        @JvmStatic
        fun getExtras(fragmentName: String?): Bundle {
            return BaseFragmentContainerActivity.getExtras(fragmentName)
        }
    }
}
