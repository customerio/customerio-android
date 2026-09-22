package io.customer.android.sample.kotlin_compose.ui.inline.compose

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.ui.theme.CustomerIoSDKTheme
import io.customer.messaginginapp.compose.InlineInAppMessage
import io.customer.messaginginapp.compose.rememberInlineMessageAvailability
import io.customer.messaginginapp.type.InAppMessage

internal const val INLINE_AVAILABILITY_LIST_TAG = "inline_availability_list"
internal const val INLINE_AVAILABILITY_STATUS_TAG = "inline_availability_status"
internal const val INLINE_CENTER_ITEM_TAG = "inline_center_item"
internal const val INLINE_BOTTOM_ITEM_TAG = "inline_bottom_item"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KotlinComposeInlineComponent(context: Context) {
    val isCenterMessageAvailable by rememberInlineMessageAvailability("compose-sticky-center")

    KotlinComposeInlineContent(
        context = context,
        isCenterMessageAvailable = isCenterMessageAvailable
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KotlinComposeInlineContent(
    context: Context,
    isCenterMessageAvailable: Boolean
) {
    /**
     * Helper function to handle in-app message actions consistently
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleMessageAction(
        location: String,
        message: InAppMessage,
        action: String,
        name: String
    ) {
        // Log the action click
        Log.d("KotlinComposeInline", "[$location] Action clicked with value: $action (name: $name)")

        // Show a toast to the user
        val toastMessage = "$location Action Value: $action"
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    CustomerIoSDKTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(INLINE_AVAILABILITY_LIST_TAG),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item(key = "availability-status") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Lazy-list inline availability",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "compose-sticky-center: " +
                                if (isCenterMessageAvailable) "available" else "unavailable",
                            modifier = Modifier.testTag(INLINE_AVAILABILITY_STATUS_TAG),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                item(key = "header-message") {
                    InlineInAppMessage(
                        elementId = "compose-sticky-header",
                        modifier = Modifier.fillMaxWidth(),
                        progressTint = MaterialTheme.colorScheme.primary,
                        onAction = { message: InAppMessage, action: String, name: String ->
                            handleMessageAction("Header", message, action, name)
                        }
                    )
                }

                item(key = "first-placeholder") {
                    PlaceholderContent(
                        modifier = Modifier.padding(16.dp)
                    )
                }

                if (isCenterMessageAvailable) {
                    item(key = "center-message") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(INLINE_CENTER_ITEM_TAG)
                        ) {
                            InlineInAppMessage(
                                elementId = "compose-sticky-center",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                                progressTint = Color(0xFF03DAC5),
                                onAction = { message: InAppMessage, action: String, name: String ->
                                    handleMessageAction("Inline", message, action, name)
                                }
                            )
                        }
                    }
                }

                item(key = "second-profile-card") {
                    ProfileCardPlaceholder()
                }

                item(key = "full-width-card") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                            .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                    )
                }

                item(key = "third-profile-card") {
                    ProfileCardPlaceholder(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    )
                }

                item(key = "bottom-message") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(INLINE_BOTTOM_ITEM_TAG)
                    ) {
                        InlineInAppMessage(
                            elementId = "compose-sticky-bottom",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            onAction = { message: InAppMessage, action: String, name: String ->
                                handleMessageAction("Below Fold", message, action, name)
                            }
                        )
                    }
                }

                item(key = "bottom-spacer") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PlaceholderContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // First profile card layout
        ProfileCardPlaceholder()

        // Full width card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .padding(top = 16.dp)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
        )

        // Three column layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // First column
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .aspectRatio(3f / 4f)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.size(8.dp))

            // Second column
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .aspectRatio(3f / 4f)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.size(8.dp))

            // Third column
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
fun ProfileCardPlaceholder(modifier: Modifier = Modifier.padding(16.dp)) {
    Row(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // Image placeholder
        Box(
            modifier = Modifier
                .width(150.dp)
                .aspectRatio(4f / 3f)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
        )

        // Content column
        Column(
            modifier = Modifier
                .padding(start = 16.dp)
                .fillMaxWidth()
        ) {
            // Title placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
            )

            // Subtitle placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(top = 8.dp)
                    .height(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
            )

            // Description placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(top = 16.dp)
                    .height(40.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
            )
        }
    }
}
