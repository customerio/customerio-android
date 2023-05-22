package io.customer.android.sample.kotlin_compose.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_DEVICE
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_PROFILE
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.SettingsIcon
import io.customer.android.sample.kotlin_compose.ui.components.VersionText
import io.customer.sdk.CustomerIO

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val userState = viewModel.uiState.collectAsState()

    DashboardScreen(
        userState = userState,
        onTrackCustomEvent = onTrackCustomEvent,
        onTrackCustomAttribute = onTrackCustomAttribute,
        onLogout = {
            viewModel.logout(user = userState.value, onLogout = onLogout)
        },
        onSettingsClick = onSettingsClick
    )
}

@Composable
fun DashboardScreen(
    userState: State<User>,
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsIcon(onSettingsClick)
            HeaderText(stringResource(R.string.hey_user, userState.value.email))
            HeaderText(stringResource(R.string.what_would_you_like_to_test))
            SendEventsView(
                onTrackCustomEvent = onTrackCustomEvent,
                onTrackCustomAttribute = onTrackCustomAttribute,
                onLogout = onLogout
            )
        }
        VersionText()
    }
}

@Composable
fun SendEventsView(
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActionButton(
            text = stringResource(R.string.send_random_event),
            modifier = Modifier.testTag("random_event"),
            onClick = {
                CustomerIO.instance().track(
                    name = "random_event",
                    attributes = mapOf("random" to (0..1000).random())
                )
            }
        )
        ActionButton(
            text = stringResource(R.string.send_custom_event),
            modifier = Modifier.testTag("send_custom_event"),
            onClick = onTrackCustomEvent
        )
        ActionButton(
            text = stringResource(R.string.set_device_attribute),
            modifier = Modifier.testTag("set_device_attribute"),
            onClick = {
                onTrackCustomAttribute.invoke(TYPE_DEVICE)
            }
        )
        ActionButton(
            text = stringResource(R.string.set_profile_attribute),
            modifier = Modifier.testTag("set_profile_attribute"),
            onClick = {
                onTrackCustomAttribute.invoke(TYPE_PROFILE)
            }
        )
        ActionButton(
            text = stringResource(R.string.logout),
            modifier = Modifier.testTag("logout"),
            onClick = onLogout
        )
    }
}
