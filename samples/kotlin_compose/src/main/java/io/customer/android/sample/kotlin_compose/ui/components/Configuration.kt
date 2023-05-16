package io.customer.android.sample.kotlin_compose.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.util.extensions.getUserAgent

@Composable
fun BoxScope.VersionText() {
    val context = LocalContext.current
    Text(
        text = context.getUserAgent(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
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
fun ActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(100.dp),
        modifier = Modifier
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
        style = MaterialTheme.typography.titleLarge
    )
}
