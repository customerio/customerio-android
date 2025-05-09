package io.customer.android.sample.java_layout.ui.inline.compose

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                // Header with title
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3F51B5))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Compose Inline In-App Example",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Header inline in-app message
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
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    factory = { ctx ->
                        InlineInAppMessageView(ctx).apply {
                            elementId = "inline"
                            setActionListener(InlineMessageActionListenerImpl(ctx, "Inline"))
                        }
                    }
                )

                // More placeholder content
                PlaceholderContent(
                    modifier = Modifier.padding(16.dp)
                )

                // Bottom inline in-app message
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
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
        // Card-like element
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
        )

        // Text placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(20.dp)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
        )

        // Smaller text placeholders
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(top = 8.dp)
                    .height(16.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(4.dp))
            )
        }

        // Another card-like element
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(100.dp)
                .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
        )

        // Information text
        Text(
            text = "This is a Jetpack Compose implementation of inline in-app messages",
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = Color.Gray
        )
    }
}