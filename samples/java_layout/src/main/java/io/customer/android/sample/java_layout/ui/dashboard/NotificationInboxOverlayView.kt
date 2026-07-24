package io.customer.android.sample.java_layout.ui.dashboard

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.customer.android.sample.java_layout.R
import io.customer.android.sample.java_layout.ui.inline.compose.ComposeTheme
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.messaginginbox.NotificationInboxOverlay

// Host-supplied custom fonts for the Visual Inbox. Keys must match the workspace theme's `fontFamily`
// tokens; Jist resolves a theme font ONLY from this map (falls back to the system font otherwise).
private val jistCustomFonts: Map<String, FontFamily> = mapOf(
    "Abril Fatface" to FontFamily(Font(R.font.abril_fatface, FontWeight.Normal)),
    "DM Sans" to FontFamily(
        Font(R.font.dm_sans_regular, FontWeight.Normal),
        Font(R.font.dm_sans_medium, FontWeight.Medium),
        Font(R.font.dm_sans_semibold, FontWeight.SemiBold),
        Font(R.font.dm_sans_bold, FontWeight.Bold)
    )
)

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
                NotificationInboxOverlay(fonts = jistCustomFonts)
            }
        }
    }
}
