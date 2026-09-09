package io.customer.android.sample.kotlin_compose.ui.inbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.customer.android.sample.kotlin_compose.ui.dashboard.jistCustomFonts
import io.customer.android.sample.kotlin_compose.ui.theme.CustomerIoSDKTheme
import io.customer.messaginginbox.NotificationInboxView

/**
 * Dedicated full-screen host for Customer.io's visual notification inbox list.
 *
 * Demonstrates the screen-style integration ([NotificationInboxView], embedded in the host's own
 * screen) as opposed to the drop-in floating bell + bottom sheet ([NotificationInboxOverlay]) mounted
 * on the dashboard. Because the host can show this screen even when the inbox has no messages, it is
 * where the SDK's empty state (a dimmed bell — no text) is visible.
 */
class VisualInboxActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CustomerIoSDKTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationInboxView(
                        modifier = Modifier.fillMaxSize(),
                        fonts = jistCustomFonts
                    )
                }
            }
        }
    }
}
