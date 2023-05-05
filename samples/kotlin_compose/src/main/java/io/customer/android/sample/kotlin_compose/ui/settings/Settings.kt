package io.customer.android.sample.kotlin_compose.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.Configuration

@Composable
fun SettingsRoute(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    val state = settingsViewModel.uiState.collectAsState()
    SettingsScreen(uiState = state.value, onBackPressed = onBackPressed, onSave = {
        settingsViewModel.saveAndUpdateConfiguration(
            configuration = it,
            application = context.applicationContext as Application,
            onComplete = {}
        )
    })
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBackPressed: () -> Unit,
    onSave: (configuration: Configuration) -> Unit
) {
    val configuration by remember { mutableStateOf(uiState.configuration) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(onBackClick = onBackPressed)
        EnvSettingsList(uiState = uiState, configuration = configuration)
        WorkspaceSettingsList(uiState = uiState, configuration = configuration)
        SDKSettingsList(uiState = uiState, configuration = configuration)
        FeaturesList(configuration = configuration)
        SaveSettings(onSaveClick = { onSave.invoke(configuration) })
    }
}

@Composable
fun SaveSettings(onSaveClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onSaveClick,
            shape = RoundedCornerShape(100.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.save),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Text(
            text = stringResource(R.string.editing_settings),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvSettingsList(uiState: SettingsUiState, configuration: Configuration) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.deviceToken,
            readOnly = true,
            onValueChange = {},
            label = {
                Text(text = stringResource(id = R.string.device_token))
            }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.configuration.trackUrl,
            onValueChange = { configuration.trackUrl = it },
            label = {
                Text(text = stringResource(id = R.string.cio_track_url))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSettingsList(uiState: SettingsUiState, configuration: Configuration) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.configuration.siteId,
            onValueChange = {
                configuration.siteId = it
            },
            label = {
                Text(text = stringResource(id = R.string.site_id))
            }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.configuration.apiKey,
            onValueChange = {
                configuration.apiKey = it
            },
            label = {
                Text(text = stringResource(id = R.string.api_key))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDKSettingsList(uiState: SettingsUiState, configuration: Configuration) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.configuration.backgroundQueueSecondsDelay.toString(),
            onValueChange = {
                if (it.isNotEmpty()) {
                    configuration.backgroundQueueSecondsDelay = it.toDouble()
                }
            },
            label = {
                Text(text = stringResource(id = R.string.background_queue_seconds_delay))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = uiState.configuration.backgroundQueueMinNumTasks.toString(),
            onValueChange = {
                configuration.backgroundQueueMinNumTasks = it.toInt()
            },
            label = {
                Text(text = stringResource(id = R.string.background_queue_min_tasks))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
fun FeaturesList(configuration: Configuration) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_screen))
            Switch(checked = configuration.trackScreen, onCheckedChange = {
                configuration.trackScreen = it
            })
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_device_attributes))
            Switch(checked = configuration.trackDeviceAttributes, onCheckedChange = {
                configuration.trackDeviceAttributes = it
            })
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.debug_mode))
            Switch(checked = configuration.debugMode, onCheckedChange = {
                configuration.debugMode = it
            })
        }
    }
}

@Composable
fun TopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
        }
        Text(
            text = stringResource(id = R.string.settings),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Preview
@Composable
fun SettingsScreenPreview() {
    SettingsScreen(uiState = SettingsUiState(), onBackPressed = {}, onSave = {})
}
