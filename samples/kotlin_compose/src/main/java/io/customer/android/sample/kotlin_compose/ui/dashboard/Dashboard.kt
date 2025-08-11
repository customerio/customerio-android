package io.customer.android.sample.kotlin_compose.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_DEVICE
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.TYPE_PROFILE
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.SettingsIcon
import io.customer.android.sample.kotlin_compose.ui.components.TrackScreenLifecycle
import io.customer.android.sample.kotlin_compose.ui.components.VersionText
import io.customer.sdk.CustomerIO
import kotlinx.coroutines.launch

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = viewModel { DashboardViewModel() },
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    onCheckPermission: () -> Unit
) {
    val userState = viewModel.uiState.collectAsState()

    TrackScreenLifecycle(lifecycleOwner = LocalLifecycleOwner.current, onScreenEnter = {
        CustomerIO.instance().screen("Dashboard")
    })

    DashboardScreen(
        userState = userState,
        onTrackCustomEvent = onTrackCustomEvent,
        onTrackCustomAttribute = onTrackCustomAttribute,
        onLogout = {
            viewModel.logout(user = userState.value, onLogout = onLogout)
        },
        onRandomEvent = {
            viewModel.sendRandomEvent()
        },
        onSettingsClick = onSettingsClick,
        onCheckPermission = onCheckPermission
    )
}

@Composable
fun DashboardScreen(
    userState: State<User>,
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onRandomEvent: () -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit,
    onCheckPermission: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsIcon(onSettingsClick)
            HeaderText(userState.value.email)
            HeaderText(stringResource(R.string.what_would_you_like_to_test))
            SendEventsView(
                onTrackCustomEvent = onTrackCustomEvent,
                onTrackCustomAttribute = onTrackCustomAttribute,
                onRandomEvent = onRandomEvent,
                showMessage = { message ->
                    scope.launch {
                        snackbarHostState.showSnackbar(message = message)
                    }
                },
                onLogout = onLogout,
                onCheckPermission = onCheckPermission
            )
        }
        VersionText()
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun SendEventsView(
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onRandomEvent: () -> Unit,
    showMessage: (String) -> Unit,
    onLogout: () -> Unit,
    onCheckPermission: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActionButton(
            text = stringResource(R.string.send_random_event),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_random_event_button)),
            onClick = {
                onRandomEvent.invoke()
                showMessage(context.getString(R.string.event_sent_successfully))
            }
        )
        ActionButton(
            text = stringResource(R.string.send_custom_event),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_custom_event_button)),
            onClick = onTrackCustomEvent
        )
        ActionButton(
            text = stringResource(R.string.set_device_attribute),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_device_attribute_button)),
            onClick = {
                onTrackCustomAttribute.invoke(TYPE_DEVICE)
            }
        )
        ActionButton(
            text = stringResource(R.string.set_profile_attribute),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_profile_attribute_button)),
            onClick = {
                onTrackCustomAttribute.invoke(TYPE_PROFILE)
            }
        )
        ActionButton(
            text = stringResource(R.string.show_push_prompt),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_push_prompt_button)),
            onClick = onCheckPermission
        )
        ActionButton(
            text = stringResource(R.string.logout),
            modifier = Modifier.testTag(stringResource(id = R.string.acd_logout_button)),
            onClick = onLogout
        )
    }
}
