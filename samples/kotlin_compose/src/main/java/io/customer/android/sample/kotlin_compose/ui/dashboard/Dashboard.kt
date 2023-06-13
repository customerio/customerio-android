package io.customer.android.sample.kotlin_compose.ui.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.material.snackbar.Snackbar
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

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val userState = viewModel.uiState.collectAsState()

    TrackScreenLifecycle(
        lifecycleOwner = LocalLifecycleOwner.current,
        onScreenEnter = {
            CustomerIO.instance().screen("Dashboard")
        }
    )

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
        onSettingsClick = onSettingsClick
    )
}

@Composable
fun DashboardScreen(
    userState: State<User>,
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onRandomEvent: () -> Unit,
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
            HeaderText(userState.value.email)
            HeaderText(stringResource(R.string.what_would_you_like_to_test))
            SendEventsView(
                onTrackCustomEvent = onTrackCustomEvent,
                onTrackCustomAttribute = onTrackCustomAttribute,
                onRandomEvent = onRandomEvent,
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
    onRandomEvent: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ActionButton(text = stringResource(R.string.send_random_event), onClick = onRandomEvent)
        ActionButton(
            text = stringResource(R.string.send_custom_event),
            onClick = onTrackCustomEvent
        )
        ActionButton(text = stringResource(R.string.set_device_attribute), onClick = {
            onTrackCustomAttribute.invoke(TYPE_DEVICE)
        })
        ActionButton(text = stringResource(R.string.set_profile_attribute), onClick = {
            onTrackCustomAttribute.invoke(TYPE_PROFILE)
        })
        ActionButton(text = stringResource(R.string.show_push_prompt), onClick = {
            activity?.requestNotificationPermission()
        })
        ActionButton(text = stringResource(R.string.logout), onClick = onLogout)
    }
}

private fun ComponentActivity.requestNotificationPermission() {
    // Push notification permission is only required by API Level 33 (Android 13) and above
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val permissionStatus = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS
    )
    // Ask for notification permission if not granted
    if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
        notificationPermissionRequestLauncher().launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        Snackbar.make(
            this.findViewById(android.R.id.content),
            R.string.notification_permission_already_granted,
            Snackbar.LENGTH_SHORT
        ).show()
    }
}

private fun ComponentActivity.notificationPermissionRequestLauncher() =
    this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        val messageId: Int =
            if (isGranted) R.string.notification_permission_success else R.string.notification_permission_failure
        Snackbar.make(this.findViewById(android.R.id.content), messageId, Snackbar.LENGTH_SHORT)
            .show()
    }
