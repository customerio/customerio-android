package io.customer.android.sample.kotlin_compose.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.Configuration
import io.customer.android.sample.kotlin_compose.ui.components.TrackScreenLifecycle
import io.customer.sdk.CustomerIO

@Composable
fun SettingsRoute(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBackPressed: () -> Unit
) {
    val state by settingsViewModel.uiState.collectAsState()

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance().screen("Settings")
    })

    SettingsScreen(configuration = state.configuration, onBackPressed = onBackPressed, onSave = {
        settingsViewModel.saveAndUpdateConfiguration(
            configuration = it
        ) {}
    }, onRestoreDefaults = {
        settingsViewModel.restoreDefaults()
    })
}

@Composable
fun SettingsScreen(
    configuration: Configuration,
    onBackPressed: () -> Unit,
    onSave: (configuration: Configuration) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(26.dp),
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopBar(onBackClick = onBackPressed)
        EnvSettingsList(
            deviceToken = "",
            trackUrl = configuration.trackUrl ?: "",
            onTrackUrlChange = {
                configuration.trackUrl = it
            }
        )
        WorkspaceSettingsList(configuration = configuration, onSiteIdChange = {
            configuration.siteId = it
        }, onApiKeyChange = {
            configuration.apiKey = it
        })
        SDKSettingsList(configuration = configuration, onBackgroundQueueMinNumTasksChange = {
            configuration.backgroundQueueMinNumTasks = it
        }, onBackgroundQueueSecondsDelayChange = {
            configuration.backgroundQueueSecondsDelay = it
        })
        FeaturesList(configuration = configuration, onTrackScreenChange = {
            configuration.trackScreen = it
        }, onTrackDeviceAttributesChange = {
            configuration.trackDeviceAttributes = it
        }, onDebugModeChange = {
            configuration.debugMode = it
        })
        SaveSettings(onSaveClick = { onSave.invoke(configuration) }, onRestoreDefaults = {
            onRestoreDefaults.invoke()
        })
    }
}

@Composable
fun SaveSettings(onSaveClick: () -> Unit, onRestoreDefaults: () -> Unit) {
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
            text = stringResource(R.string.restore_defaults),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(4.dp)
                .clickable { onRestoreDefaults.invoke() }
                .clip(RoundedCornerShape(25.dp)),
            fontWeight = FontWeight.Bold

        )
        Text(
            text = stringResource(R.string.editing_settings),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvSettingsList(
    deviceToken: String,
    trackUrl: String,
    onTrackUrlChange: (trackUrl: String) -> Unit
) {
    var trackUrlState by remember { mutableStateOf(trackUrl) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = deviceToken,
            readOnly = true,
            onValueChange = {},
            label = {
                Text(text = stringResource(id = R.string.device_token))
            }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = trackUrlState,
            onValueChange = {
                trackUrlState = it
                onTrackUrlChange(it)
            },
            label = {
                Text(text = stringResource(id = R.string.cio_track_url))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSettingsList(
    configuration: Configuration,
    onSiteIdChange: (siteId: String) -> Unit,
    onApiKeyChange: (apiKey: String) -> Unit
) {
    var siteId by remember { mutableStateOf(configuration.siteId) }
    var apiKey by remember { mutableStateOf(configuration.apiKey) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = siteId, onValueChange = {
            siteId = it
            onSiteIdChange(it)
        }, label = {
            Text(text = stringResource(id = R.string.site_id))
        })
        OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = apiKey, onValueChange = {
            apiKey = it
            onApiKeyChange(it)
        }, label = {
            Text(text = stringResource(id = R.string.api_key))
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDKSettingsList(
    configuration: Configuration,
    onBackgroundQueueSecondsDelayChange: (delay: Double) -> Unit,
    onBackgroundQueueMinNumTasksChange: (numTasks: Int) -> Unit
) {
    var secondsDelays by remember { mutableStateOf(configuration.backgroundQueueSecondsDelay) }
    var minTasks by remember { mutableStateOf(configuration.backgroundQueueMinNumTasks) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = secondsDelays.toString(),
            onValueChange = {
                if (it.isNotEmpty()) {
                    secondsDelays = it.toDouble()
                    onBackgroundQueueSecondsDelayChange(it.toDouble())
                }
            },
            label = {
                Text(text = stringResource(id = R.string.background_queue_seconds_delay))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = minTasks.toString(),
            onValueChange = {
                if (it.isNotEmpty()) {
                    minTasks = it.toInt()
                    onBackgroundQueueMinNumTasksChange(it.toInt())
                }
            },
            label = {
                Text(text = stringResource(id = R.string.background_queue_min_tasks))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
fun FeaturesList(
    configuration: Configuration,
    onTrackScreenChange: (trackScreen: Boolean) -> Unit,
    onTrackDeviceAttributesChange: (trackDeviceAttributes: Boolean) -> Unit,
    onDebugModeChange: (debugMode: Boolean) -> Unit
) {
    var trackScreen by remember { mutableStateOf(configuration.trackScreen) }
    var trackDeviceAttributes by remember { mutableStateOf(configuration.trackDeviceAttributes) }
    var debugMode by remember { mutableStateOf(configuration.debugMode) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_screen))
            Switch(checked = trackScreen, onCheckedChange = {
                trackScreen = it
                onTrackScreenChange(it)
            })
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_device_attributes))
            Switch(checked = trackDeviceAttributes, onCheckedChange = {
                trackDeviceAttributes = it
                onTrackDeviceAttributesChange(it)
            })
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.debug_mode))
            Switch(checked = debugMode, onCheckedChange = {
                debugMode = it
                onDebugModeChange(it)
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
    SettingsScreen(
        configuration = Configuration("", ""),
        onBackPressed = {},
        onSave = {},
        onRestoreDefaults = {}
    )
}
