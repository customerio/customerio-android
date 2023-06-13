package io.customer.android.sample.kotlin_compose.ui.customTrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.TrackScreenLifecycle
import io.customer.sdk.CustomerIO

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEventRoute(
    onBackPressed: () -> Unit
) {
    var eventName by remember { mutableStateOf("") }
    var eventError by remember { mutableStateOf("") }
    var propertyName by remember { mutableStateOf("") }
    var propertyValue by remember { mutableStateOf("") }

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance().screen("Custom Event")
    })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        IconButton(
            onClick = onBackPressed
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.align(Alignment.Start)
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            HeaderText(stringResource(R.string.send_custom_event))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = eventName,
                onValueChange = {
                    eventName = it
                    eventError = ""
                },
                label = {
                    Text(text = stringResource(id = R.string.event_name))
                },
                isError = eventError.isNotEmpty(),
                supportingText = {
                    if (eventError.isNotEmpty()) {
                        Text(text = eventError)
                    }
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = propertyName,
                onValueChange = {
                    propertyName = it
                },
                label = {
                    Text(text = stringResource(id = R.string.propety_name))
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = propertyValue,
                onValueChange = {
                    propertyValue = it
                },
                label = {
                    Text(text = stringResource(id = R.string.property_value))
                }
            )
            ActionButton(text = stringResource(R.string.send_event), onClick = {
                if (eventName.isEmpty()) {
                    eventError = "Required"
                    return@ActionButton
                }
                CustomerIO.instance().track(
                    name = eventName,
                    attributes = mapOf(propertyName to propertyValue)
                )
            })
        }
    }
}
