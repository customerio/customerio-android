package io.customer.android.sample.kotlin_compose.ui.customTrack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_PROFILE
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.BackButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.sdk.CustomerIO

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAttributeRoute(
    onBackPressed: () -> Unit,
    attributeType: String
) {
    var attributeName by remember { mutableStateOf("") }
    var attributeValue by remember { mutableStateOf("") }

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
            HeaderText(
                string = if (attributeType == TYPE_PROFILE) {
                    stringResource(R.string.set_profile_attribute)
                } else {
                    stringResource(R.string.set_device_attribute)
                }
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attribute_name"),
                value = attributeName,
                onValueChange = {
                    attributeName = it
                },
                label = {
                    Text(text = stringResource(id = R.string.attribute_name))
                }
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attribute_value"),
                value = attributeValue,
                onValueChange = {
                    attributeValue = it
                },
                label = {
                    Text(text = stringResource(id = R.string.attribute_value))
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
            ActionButton(
                text = btnTitle,
                modifier = Modifier.testTag("send_button"),
                onClick = action
            )
        }
    }
}
