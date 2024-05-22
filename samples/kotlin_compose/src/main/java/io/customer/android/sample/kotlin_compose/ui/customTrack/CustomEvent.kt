package io.customer.android.sample.kotlin_compose.ui.customTrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.BackButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.TrackScreenLifecycle
import io.customer.sdk.CustomerIO
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEventRoute(
    onBackPressed: () -> Unit
) {
    var eventName by remember { mutableStateOf("") }
    var propertyName by remember { mutableStateOf("") }
    var propertyValue by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance().screen("Custom Event")
    })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        BackButton(onClick = onBackPressed)

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            HeaderText(stringResource(R.string.send_custom_event))
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(stringResource(id = R.string.acd_event_name_input)),
                value = eventName,
                onValueChange = {
                    eventName = it
                },
                label = {
                    Text(text = stringResource(id = R.string.event_name))
                }
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(stringResource(id = R.string.acd_property_name_input)),
                value = propertyName,
                onValueChange = {
                    propertyName = it
                },
                label = {
                    Text(text = stringResource(id = R.string.propety_name))
                }
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(stringResource(id = R.string.acd_property_value_input)),
                value = propertyValue,
                onValueChange = {
                    propertyValue = it
                },
                label = {
                    Text(text = stringResource(id = R.string.property_value))
                }
            )
            ActionButton(
                text = stringResource(R.string.send_event),
                modifier = Modifier.testTag(stringResource(id = R.string.acd_send_event_button)),
                onClick = {
                    CustomerIO.instance().track(
                        name = eventName,
                        properties = if (propertyName.isEmpty()) emptyMap() else mapOf(propertyName to propertyValue)
                    )
                    scope.launch {
                        snackbarHostState.showSnackbar(message = context.getString(R.string.event_sent_successfully))
                    }
                }
            )
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
