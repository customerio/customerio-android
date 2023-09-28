package io.customer.android.sample.kotlin_compose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val notificationSettingsRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (isNotificationPermissionGranted()) {
            showMessage(getString(R.string.notification_permission_success))
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun isNotificationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionRequestLauncher = this.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            val messageId: Int =
                if (isGranted) R.string.notification_permission_success else R.string.notification_permission_failure
            showMessage(getString(messageId))

            if (isGranted) {
                showMessage(getString(R.string.notification_permission_success))
            } else {
                val builder: MaterialAlertDialogBuilder =
                    MaterialAlertDialogBuilder(this).setCancelable(true)
                        .setPositiveButton(android.R.string.ok, null)
                builder.setMessage(R.string.notification_permission_failure)
                builder.setNeutralButton(R.string.open_settings) { _, _ -> openNotificationPermissionSettings() }
                builder.show()
            }
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
        Snackbar.make(this.findViewById(android.R.id.content), string, Snackbar.LENGTH_SHORT).show()
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private fun openNotificationPermissionSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        notificationSettingsRequestLauncher.launch(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        (application as MainApplication).lifecycleEventsListener.logEvent("onNewIntent", this)
        deepLinkIntentState.tryEmit(intent)
    }
}
