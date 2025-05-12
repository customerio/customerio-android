package io.customer.android.sample.java_layout.ui.inline.compose

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.customer.android.sample.java_layout.ui.core.BaseComposeFragment
import io.customer.android.sample.java_layout.ui.inline.InlineMessageActionListenerImpl
import io.customer.messaginginapp.ui.InlineInAppMessageView

/**
 * A fragment that demonstrates how to use InlineInAppMessageView with Jetpack Compose
 * in a Java-based app.
 */
class ComposeInlineExampleFragment : BaseComposeFragment() {

    override fun createComposeView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                ComposeInlineExampleScreen(requireContext())
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance() = ComposeInlineExampleFragment()
    }
}

@Composable
fun ComposeInlineExampleScreen(context: Context) {
    ComposeTheme {
        Surface(color = MaterialTheme.colors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // No header to match XML layout

                // Header inline in-app message (sticky header)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    factory = { ctx ->
                        InlineInAppMessageView(ctx).apply {
                            elementId = "sticky-header"
                            setActionListener(InlineMessageActionListenerImpl(ctx, "Header"))
                        }
                    }
                )

                // Content area with placeholder elements
                PlaceholderContent(
                    modifier = Modifier.padding(16.dp)
                )

                // Middle inline in-app message
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    factory = { ctx ->
                        InlineInAppMessageView(ctx).apply {
                            elementId = "inline"
                            setActionListener(InlineMessageActionListenerImpl(ctx, "Inline"))
                        }
                    }
                )

                // Second profile card layout
                ProfileCardPlaceholder()

                // Full width card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                        .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                )

                // Third profile card layout
                ProfileCardPlaceholder(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))

                // Bottom inline in-app message (below fold)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    factory = { ctx ->
                        InlineInAppMessageView(ctx).apply {
                            elementId = "below-fold"
                            setActionListener(InlineMessageActionListenerImpl(ctx, "Below Fold"))
                        }
                    }
                )
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
