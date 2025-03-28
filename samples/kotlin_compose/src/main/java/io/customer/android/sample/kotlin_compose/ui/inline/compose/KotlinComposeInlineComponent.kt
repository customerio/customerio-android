package io.customer.android.sample.kotlin_compose.ui.inline.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.customer.android.sample.kotlin_compose.R
import io.customer.messaginginapp.gist.data.model.Message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KotlinComposeInlineComponent(
    stickHeaderMessage: Message?,
    inlineMessage: Message?,
    belowFoldMessage: Message?,
    onFetchMessagesClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            stickHeaderMessage?.let {
                InlineMessageBaseView(message = it)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(paddingValues)
                .padding(dimensionResource(id = R.dimen.margin_default))
        ) {
            Button(
                onClick = onFetchMessagesClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .widthIn(max = dimensionResource(id = R.dimen.material_button_max_width))
            ) {
                Text("Fetch Inline Messages")
            }

            Spacer(modifier = Modifier.height(dimensionResource(id = R.dimen.margin_default)))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.Blue)
            )

            Spacer(modifier = Modifier.height(dimensionResource(id = R.dimen.margin_small)))

            inlineMessage?.let {
                InlineMessageBaseView(message = it)
            }

            Spacer(modifier = Modifier.height(dimensionResource(id = R.dimen.margin_default)))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color.Blue)
            )

            Spacer(modifier = Modifier.height(dimensionResource(id = R.dimen.margin_small)))

            belowFoldMessage?.let {
                InlineMessageBaseView(message = it)
            }
        }
    }
}

@Composable
fun InlineMessageBaseView(
    modifier: Modifier = Modifier,
    message: Message
) {
    AndroidView(
        factory = { context ->
            io.customer.messaginginapp.inline.InlineMessageBaseView(context)
        },
        modifier = modifier,
        update = { view ->
            view.showMessage(message)
        }
    )
}
