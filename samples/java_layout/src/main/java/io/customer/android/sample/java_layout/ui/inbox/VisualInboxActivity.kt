package io.customer.android.sample.java_layout.ui.inbox

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import io.customer.android.sample.java_layout.databinding.ActivityVisualInboxBinding
import io.customer.android.sample.java_layout.ui.core.BaseActivity
import io.customer.android.sample.java_layout.ui.dashboard.visualInboxFonts
import io.customer.android.sample.java_layout.ui.inline.compose.ComposeTheme
import io.customer.messaginginbox.NotificationInboxView

/**
 * Dedicated full-screen host for Customer.io's visual notification inbox list.
 *
 * This demonstrates the screen-style integration customers can use instead of the convenience
 * floating-bell overlay. Message content still comes from Customer.io and is rendered by Jist.
 */
class VisualInboxActivity : BaseActivity<ActivityVisualInboxBinding>() {
    override fun inflateViewBinding(): ActivityVisualInboxBinding =
        ActivityVisualInboxBinding.inflate(layoutInflater)

    override fun setupContent() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.visualInboxContent.setContent {
            ComposeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    NotificationInboxView(
                        modifier = Modifier.fillMaxSize(),
                        fonts = visualInboxFonts
                    )
                }
            }
        }
    }
}
