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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_PROFILE
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.TrackScreenLifecycle
import io.customer.sdk.CustomerIO
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAttributeRoute(
    onBackPressed: () -> Unit,
    attributeType: String
) {
    var attributeName by remember { mutableStateOf("") }
    var attributeValue by remember { mutableStateOf("") }
    var attributeNameError by remember { mutableStateOf("") }
    var attributeValueError by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance()
            .screen("Custom ${attributeType.replaceFirstChar { it.uppercase() }} Attribute")
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
            HeaderText(
                string = if (attributeType == TYPE_PROFILE) {
                    stringResource(R.string.set_profile_attribute)
                } else {
                    stringResource(R.string.set_device_attribute)
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = attributeName,
                onValueChange = {
                    attributeName = it
                    attributeNameError = ""
                },
                label = {
                    Text(text = stringResource(id = R.string.attribute_name))
                },
                isError = attributeNameError.isNotEmpty(),
                supportingText = {
                    if (attributeNameError.isNotEmpty()) {
                        Text(text = attributeNameError)
                    }
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = attributeValue,
                onValueChange = {
                    attributeValue = it
                    attributeValueError = ""
                },
                label = {
                    Text(text = stringResource(id = R.string.attribute_value))
                },
                isError = attributeValueError.isNotEmpty(),
                supportingText = {
                    if (attributeValueError.isNotEmpty()) {
                        Text(text = attributeValueError)
                    }
                }
            )

            val (btnTitle, action) = if (attributeType == TYPE_PROFILE) {
                Pair(
                    stringResource(R.string.send_profile_attribute)
                ) {
                    CustomerIO.instance().profileAttributes = mapOf(attributeName to attributeValue)
                }
            } else {
                Pair(stringResource(R.string.send_device_attribute)) {
                    CustomerIO.instance().deviceAttributes = mapOf(attributeName to attributeValue)
                }
            }
            ActionButton(text = btnTitle, onClick = {
                if (attributeName.isEmpty()) {
                    attributeNameError = "Required"
                    return@ActionButton
                }
                if (attributeValue.isEmpty()) {
                    attributeValueError = "Required"
                    return@ActionButton
                }
                action.invoke()
                scope.launch {
                    snackbarHostState.showSnackbar(message = context.getString(R.string.attribute_set_successfully))
                }
            })
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
