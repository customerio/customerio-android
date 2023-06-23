package io.customer.android.sample.kotlin_compose

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.customer.android.sample.kotlin_compose.navigation.AppNavGraph
import io.customer.android.sample.kotlin_compose.ui.login.AuthenticationViewModel
import io.customer.android.sample.kotlin_compose.ui.theme.CustomerIoSDKTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authenticationViewModel: AuthenticationViewModel by viewModels()
    private val deepLinkIntentState: MutableStateFlow<Intent?> = MutableStateFlow(null)
    lateinit var notificationPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionRequestLauncher = this.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            val messageId: Int =
                if (isGranted) R.string.notification_permission_success else R.string.notification_permission_failure
            showMessage(getString(messageId))
        }

        setContent {
            CustomerIoSDKTheme {
                val navController = rememberNavController()

                val navBackStackEntry by navController.currentBackStackEntryAsState()

                val currentRoute = navBackStackEntry?.destination?.route
                    ?: authenticationViewModel.getDestination()

                AppNavGraph(
                    startDestination = currentRoute,
                    deepLinkState = deepLinkIntentState.asStateFlow(),
                    modifier = Modifier.systemBarsPadding(),
                    onCheckPermission = ::launchNotificationPermissionRequest
                )
            }
        }
    }

    private fun launchNotificationPermissionRequest() {
        // Push notification permission is only required by API Level 33 (Android 13) and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showMessage(getString(R.string.notification_permission_already_granted))
            return
        }

        val permissionStatus = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        // Ask for notification permission if not granted
        if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequestLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showMessage(getString(R.string.notification_permission_already_granted))
        }
    }

    private fun showMessage(string: String) {
        Snackbar.make(this.findViewById(android.R.id.content), string, Snackbar.LENGTH_SHORT)
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        deepLinkIntentState.tryEmit(intent)
    }
}
