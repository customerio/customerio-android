package io.customer.android.sample.kotlin_compose.ui.settings

import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
    cdpApiKey: String? = null,
    siteId: String? = null,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onBackPressed: () -> Unit
) {
    val state by settingsViewModel.uiState.collectAsState()

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance().screen("Settings")
    })

    SettingsScreen(
        cdpApiKey = cdpApiKey,
        siteId = siteId,
        uiState = state,
        onBackPressed = onBackPressed,
        onSave = {
            settingsViewModel.saveAndUpdateConfiguration(
                configuration = it
            ) {}
        },
        onConfigurationChange = {
            settingsViewModel.updateConfiguration(
                configuration = it
            ) {}
        },
        onRestoreDefaults = {
            settingsViewModel.restoreDefaults()
        }
    )
}

@Composable
fun SettingsScreen(
    cdpApiKey: String? = null,
    siteId: String? = null,
    uiState: SettingsUiState,
    onBackPressed: () -> Unit,
    onConfigurationChange: (configuration: Configuration) -> Unit,
    onSave: (configuration: Configuration) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    val configuration = uiState.configuration

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
            uiState = uiState,
            onConfigurationChange = onConfigurationChange
        )
        WorkspaceSettingsList(
            cdpApiKey = cdpApiKey,
            siteId = siteId,
            uiState = uiState,
            onConfigurationChange = onConfigurationChange
        )
        SDKSettingsList(
            uiState = uiState,
            onConfigurationChange = onConfigurationChange
        )
        FeaturesList(
            uiState = uiState,
            onConfigurationChange = onConfigurationChange
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_save_settings_button))
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
                .clip(RoundedCornerShape(25.dp))
                .testTag(stringResource(id = R.string.acd_restore_default_settings_button)),
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
    uiState: SettingsUiState,
    onConfigurationChange: (configuration: Configuration) -> Unit
) {
    val configuration = uiState.configuration
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_device_token_input)),
            value = uiState.deviceToken,
            singleLine = true,
            readOnly = true,
            onValueChange = {},
            label = {
                Text(text = stringResource(id = R.string.device_token))
            },
            trailingIcon = {
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(uiState.deviceToken))
                    Toast.makeText(
                        context,
                        context.getString(R.string.token_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.baseline_content_copy_24),
                        contentDescription = "Copy Text",
                        tint = Color.Gray
                    )
                }
            }
        )
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_api_host_input)),
            value = configuration.apiHost ?: "",
            onValueChange = { value ->
                onConfigurationChange(configuration.copy(apiHost = value))
            },
            label = {
                Text(text = stringResource(id = R.string.api_host))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = uiState.customAPIHostError.isNotEmpty(),
            supportingText = {
                if (uiState.customAPIHostError.isNotEmpty()) {
                    Text(text = uiState.customAPIHostError)
                }
            }
        )
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_cdn_host_input)),
            value = configuration.cdnHost ?: "",
            onValueChange = { value ->
                onConfigurationChange(configuration.copy(cdnHost = value))
            },
            label = {
                Text(text = stringResource(id = R.string.cdn_host))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = uiState.customCDNHostError.isNotEmpty(),
            supportingText = {
                if (uiState.customCDNHostError.isNotEmpty()) {
                    Text(text = uiState.customCDNHostError)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSettingsList(
    cdpApiKey: String? = null,
    siteId: String? = null,
    uiState: SettingsUiState,
    onConfigurationChange: (configuration: Configuration) -> Unit
) {
    val configuration = uiState.configuration

    LaunchedEffect(key1 = cdpApiKey, key2 = siteId) {
        if (cdpApiKey != null && siteId != null) {
            onConfigurationChange(configuration.copy(cdpApiKey = cdpApiKey, siteId = siteId))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_cdp_api_key_input)),
            value = configuration.cdpApiKey,
            onValueChange = { value ->
                onConfigurationChange(configuration.copy(cdpApiKey = value))
            },
            label = {
                Text(text = stringResource(id = R.string.cdp_api_key))
            }
        )
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_site_id_input)),
            value = configuration.siteId,
            onValueChange = { value ->
                onConfigurationChange(configuration.copy(siteId = value))
            },
            label = {
                Text(text = stringResource(id = R.string.site_id))
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDKSettingsList(
    uiState: SettingsUiState,
    onConfigurationChange: (configuration: Configuration) -> Unit
) {
    val configuration = uiState.configuration

    var flushIntervalTextValue by remember { mutableStateOf(configuration.flushInterval.toString()) }
    var flushAtTextValue by remember { mutableStateOf(configuration.flushAt.toString()) }
    var errorState by remember { mutableStateOf("") }

    LaunchedEffect(configuration.flushInterval) {
        flushIntervalTextValue = configuration.flushInterval.toString()
        flushAtTextValue = configuration.flushAt.toString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_flush_interval_input)),
            value = flushIntervalTextValue,
            maxLines = 1,
            isError = errorState.isNotEmpty(),
            onValueChange = { value ->
                flushIntervalTextValue = value
                val parsedValue = value.toIntOrNull()
                if (parsedValue == null || parsedValue < 1) {
                    errorState = "Please enter a valid number greater than 1"
                } else {
                    errorState = ""
                    onConfigurationChange(configuration.copy(flushInterval = parsedValue))
                }
            },
            label = {
                Text(text = stringResource(id = R.string.flush_interval))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Decimal),
            supportingText = {
                if (errorState.isNotEmpty()) {
                    Text(text = errorState)
                }
            }
        )
        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(stringResource(id = R.string.acd_flush_at_input)),
            maxLines = 1,
            value = flushAtTextValue,
            onValueChange = { value ->
                flushAtTextValue = value
                val parsedValue = value.toIntOrNull()
                if (parsedValue == null || parsedValue < 1) {
                    errorState = "Please enter a valid number greater than 1"
                } else {
                    errorState = ""
                    onConfigurationChange(configuration.copy(flushAt = parsedValue))
                }
            },
            label = {
                Text(text = stringResource(id = R.string.flush_at))
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
fun FeaturesList(
    uiState: SettingsUiState,
    onConfigurationChange: (configuration: Configuration) -> Unit
) {
    val configuration = uiState.configuration
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_screen))
            Switch(checked = configuration.trackScreen, onCheckedChange = { value ->
                onConfigurationChange(configuration.copy(trackScreen = value))
            }, modifier = Modifier.testTag(stringResource(id = R.string.acd_track_screens_switch)))
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.track_device_attributes))
            Switch(
                checked = configuration.trackDeviceAttributes,
                onCheckedChange = { value ->
                    onConfigurationChange(configuration.copy(trackDeviceAttributes = value))
                },
                modifier = Modifier.testTag(stringResource(id = R.string.acd_track_device_attributes_switch))
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.debug_mode))
            Switch(checked = configuration.debugMode, onCheckedChange = { value ->
                onConfigurationChange(configuration.copy(debugMode = value))
            }, modifier = Modifier.testTag(stringResource(id = R.string.acd_debug_mode_switch)))
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
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.testTag(stringResource(id = R.string.acd_back_button_icon))
            )
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
        uiState = SettingsUiState(configuration = Configuration("", "", "")),
        onBackPressed = {},
        onSave = {},
        onConfigurationChange = {},
        onRestoreDefaults = {}
    )
}
