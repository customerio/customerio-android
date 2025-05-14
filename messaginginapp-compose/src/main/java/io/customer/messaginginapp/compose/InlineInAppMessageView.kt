package io.customer.messaginginapp.compose

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.DrawableCompat
import io.customer.messaginginapp.type.InAppMessage
import io.customer.messaginginapp.type.InlineMessageActionListener
import io.customer.messaginginapp.ui.bridge.AndroidInAppPlatformDelegate
import io.customer.messaginginapp.ui.bridge.InAppHostViewDelegateImpl
import io.customer.messaginginapp.ui.bridge.InlineInAppMessageViewCallback
import io.customer.messaginginapp.ui.controller.InlineInAppMessageViewController
import kotlinx.coroutines.android.awaitFrame

import io.customer.base.internal.InternalCustomerIOApi

/**
 * A Composable that displays an inline in-app message for a given element ID.
 * 
 * This composable integrates with CustomerIO's in-app messaging system to display 
 * targeted messages in a Jetpack Compose UI. It leverages the existing controller 
 * and view model architecture to ensure consistent behavior with the XML-based views.
 *
 * @param elementId The element ID that this view will display messages for
 * @param modifier The modifier to be applied to the composable
 * @param progressTint Optional color to use for the loading indicator. If not specified, 
 *                     the system's colorControlActivated will be used
 * @param onAction Optional callback that will be invoked when a message action is clicked
 */
@Composable
fun InlineInAppMessageView(
    elementId: String,
    modifier: Modifier = Modifier,
    progressTint: androidx.compose.ui.graphics.Color? = null,
    onAction: (action: String) -> Unit = {}
) {
    val context = LocalContext.current

    // Create a FrameLayout to host the WebView content
    val viewContainer = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // Set a minimum height to ensure visibility
            minimumHeight = (context.resources.displayMetrics.density * 48).toInt()
        }
    }

    // Create controller and delegates only once per element ID
    val controller = remember(elementId) {
        // Create view and platform delegates
        val viewDelegate = InAppHostViewDelegateImpl(viewContainer)
        val platformDelegate = AndroidInAppPlatformDelegate(viewContainer)
        
        // Create and configure the controller
        InlineInAppMessageViewController(viewDelegate, platformDelegate).apply {
            this.elementId = elementId
            this.actionListener = object : InlineMessageActionListener {
                override fun onActionClick(
                    message: InAppMessage, 
                    currentRoute: String, 
                    action: String, 
                    name: String
                ) {
                    onAction(action)
                }
            }
        }
    }

    var isLoading by remember { mutableStateOf(false) }
    
    val defaultProgressColor = remember {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorControlActivated, typedValue, true)
        typedValue.data.takeIf { it != 0 } ?: Color.GRAY
    }
    val progressColor = progressTint?.toArgb() ?: defaultProgressColor
    
    // Create progress indicator that will be added to the FrameLayout
    val progressIndicator = remember {
        ProgressBar(context).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            
            runCatching {
                indeterminateDrawable?.mutate()?.let { drawable ->
                    DrawableCompat.setTint(
                        DrawableCompat.wrap(drawable),
                        progressColor
                    )
                }
            }
        }
    }
    
    // Add progress indicator to the container
    LaunchedEffect(Unit) {
        viewContainer.addView(progressIndicator)
    }
    
    // Set up the view callback and WebView just once
    LaunchedEffect(elementId) {
        // Set up the view callback to handle view state changes
        controller.viewCallback = object : InlineInAppMessageViewCallback {
            override fun onLoadingStarted() {
                isLoading = true
                progressIndicator.visibility = android.view.View.VISIBLE
                progressIndicator.bringToFront()
            }

            override fun onLoadingFinished() {
                isLoading = false
                progressIndicator.visibility = android.view.View.GONE
            }

            override fun onNoMessageToDisplay() {
                isLoading = false
                progressIndicator.visibility = android.view.View.GONE
            }

            override fun onViewSizeChanged(width: Int, height: Int) {
                // Size has changed, nothing to do in Compose as the FrameLayout handles it
            }
        }

        // Wait for the first frame to ensure the view container is properly laid out
        awaitFrame()
        
        // Initialize the WebView for this element
        val webViewDelegate = controller.viewDelegate.createEngineWebViewInstance()
        controller.viewDelegate.addView(webViewDelegate)
    }

    // Clean up when the composable is disposed
    DisposableEffect(elementId) {
        onDispose {
            // Let the controller handle cleanup when needed
            // No need to explicitly clean up the WebView here
        }
    }

    AndroidView(
        factory = { viewContainer },
        modifier = modifier.fillMaxWidth()
    )
}
