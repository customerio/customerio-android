package io.customer.android.sample.kotlin_compose.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.util.extensions.getUserAgent

@Composable
fun BoxScope.VersionText() {
    Text(
        text = getUserAgent(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
        fontSize = MaterialTheme.typography.bodySmall.fontSize
    )
}

@Composable
fun ColumnScope.SettingsIcon(onSettingsClick: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = stringResource(R.string.settings),
        modifier = Modifier
            .size(72.dp)
            .align(Alignment.End)
            .padding(16.dp)
            .clickable(onClick = onSettingsClick)
    )
}

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(100.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1
        )
    }
}

@Composable
fun HeaderText(string: String) {
    Text(
        text = string,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun TrackScreenLifecycle(
    lifecycleOwner: LifecycleOwner,
    onScreenEnter: () -> Unit = {},
    onScreenExit: () -> Unit = {}
) {
    LaunchedEffect(key1 = lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                onScreenEnter()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                onScreenExit()
            }
        })
    }
}

@Composable
fun ColumnScope.BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick
    ) {
        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            modifier = Modifier
                .align(Alignment.Start)
                .testTag(stringResource(id = R.string.acd_back_button_icon))
        )
    }
}
