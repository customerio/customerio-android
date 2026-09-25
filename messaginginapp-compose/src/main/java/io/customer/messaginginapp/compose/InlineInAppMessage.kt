package io.customer.messaginginapp.compose

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.InlineInAppMessageView
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Remembers whether an inline in-app message is currently available for [elementId].
 *
 * The state starts as `false` and updates with the current message queue, route, and message
 * lifecycle. Reading it has no side effects: it does not fetch, display, or mark a message as shown.
 * If the in-app messaging module has not been initialized, the state remains `false` rather than
 * failing composition. Initialize Customer.io before composing this helper to receive later updates.
 * This makes it suitable for conditionally adding an item to a lazy layout:
 *
 * ```
 * val isAvailable by rememberInlineMessageAvailability("promotion")
 * LazyColumn {
 *     if (isAvailable) {
 *         item { InlineInAppMessage(elementId = "promotion") }
 *     }
 * }
 * ```
 *
 * @param elementId The element ID configured for the inline message in Customer.io.
 */
@Composable
fun rememberInlineMessageAvailability(elementId: String): State<Boolean> {
    val availability = remember(elementId) {
        inlineMessageAvailabilityFlow(elementId)
    }
    return availability.collectAsStateWithLifecycle(initialValue = false)
}

internal fun inlineMessageAvailabilityFlow(elementId: String): Flow<Boolean> =
    try {
        ModuleMessagingInApp.instance().observeInlineMessageAvailability(elementId)
    } catch (_: IllegalStateException) {
        flowOf(false)
    }

/**
 * A Composable that displays an inline in-app message for a given element ID.
 *
 * This composable integrates with CustomerIO's in-app messaging system to display
 * targeted messages in a Jetpack Compose UI. It leverages the existing InlineInAppMessageView
 * to ensure consistent behavior with the XML-based views.
 *
 * @param elementId The element ID that this view will display messages for
 * @param modifier The modifier to be applied to the composable
 * @param progressTint Optional color to use for the loading indicator. If not specified,
 *                     the system's colorControlActivated will be used
 * @param onAction Optional callback that will be invoked when a message action is clicked
 */
@Composable
fun InlineInAppMessage(
    elementId: String,
    modifier: Modifier = Modifier,
    progressTint: androidx.compose.ui.graphics.Color? = null,
    onAction: (message: InAppMessage, actionValue: String, actionName: String) -> Unit = { _, _, _ ->
    }
) {
    val context = LocalContext.current

    // Use the existing InlineInAppMessageView implementation with explicit controller initialization
    val view = remember {
        InlineInAppMessageView(context).apply {
            // Set proper layout parameters
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // Set custom progress tint if provided
            progressTint?.let { color ->
                setProgressTint(color.toArgb())
            }
        }
    }

    // Update progress tint when it changes
    LaunchedEffect(progressTint) {
        progressTint?.let { color ->
            view.setProgressTint(color.toArgb())
        }
    }

    // Configure the action listener
    LaunchedEffect(elementId, onAction) {
        view.setActionListener(object : InlineMessageActionListener {
            override fun onActionClick(
                message: InAppMessage,
                actionValue: String,
                actionName: String
            ) {
                onAction(message, actionValue, actionName)
            }
        })
    }

    // Set the element ID and ensure proper initialization
    LaunchedEffect(elementId) {
        view.elementId = elementId

        // Wait for the view to be properly laid out
        awaitFrame()
    }

    AndroidView(
        factory = { view },
        modifier = modifier.fillMaxWidth()
    )
}
