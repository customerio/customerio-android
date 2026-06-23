package io.customer.android.sample.java_layout.ui.dashboard

import androidx.compose.ui.platform.ComposeView
import io.customer.android.sample.java_layout.ui.inline.compose.ComposeTheme
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginbox.NotificationInboxOverlay

/**
 * Helper for the Java-based dashboard to mount the Compose-based [NotificationInboxOverlay]
 * inside an existing [ComposeView]. Setting Compose content directly from Java is awkward
 * (composable lambdas), so this wraps the overlay in the sample's [ComposeTheme] and exposes
 * a plain Java-callable entry point.
 *
 * The overlay reads from the visual-inbox data layer (an `@InternalCustomerIOApi`), so the
 * sample opts in here.
 */
object NotificationInboxOverlayView {

    /**
     * Sets the content of the given [composeView] to the visual notification inbox overlay,
     * wrapped in the sample app's Compose theme. The overlay shows its floating bell only when
     * the data layer reports the inbox as visible (enabled + renderable).
     */
    @JvmStatic
    @OptIn(InternalCustomerIOApi::class)
    fun mount(composeView: ComposeView) {
        composeView.setContent {
            ComposeTheme {
                NotificationInboxOverlay()
            }
        }
    }
}
