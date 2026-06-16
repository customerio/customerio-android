package io.customer.android.sample.java_layout.ui.dashboard

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import io.customer.android.sample.java_layout.ui.inline.compose.ComposeTheme
import io.customer.messaginginbox.NotificationInboxOverlay

/**
 * Helper for the Java-based dashboard to mount the Compose-based [NotificationInboxOverlay]
 * inside an existing [ComposeView]. Setting Compose content directly from Java is awkward
 * (composable lambdas), so this wraps the overlay in the sample's [ComposeTheme] and exposes
 * a plain Java-callable entry point.
 */
object NotificationInboxOverlayView {

    /**
     * Sets the content of the given [composeView] to the visual notification inbox overlay,
     * wrapped in the sample app's Compose theme. The overlay hides itself automatically when
     * there are no inbox messages.
     */
    @JvmStatic
    fun mount(composeView: ComposeView) {
        composeView.setContent {
            ComposeTheme {
                NotificationInboxOverlay()
            }
        }
    }

    /**
     * Convenience that creates a [ComposeView] for the given [context] already configured with
     * the overlay content.
     */
    @JvmStatic
    fun create(context: Context): ComposeView = ComposeView(context).apply { mount(this) }
}
