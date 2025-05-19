package io.customer.messaginginapp.compose

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.InlineInAppMessageView
import kotlinx.coroutines.android.awaitFrame

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
    onAction: (message: InAppMessage, currentRoute: String, action: String, name: String) -> Unit = { _, _, _, _ ->
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

            // Set a minimum height to ensure visibility
            minimumHeight = (context.resources.displayMetrics.density * 48).toInt()

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
                currentRoute: String,
                action: String,
                name: String
            ) {
                onAction(message, currentRoute, action, name)
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
