package io.customer.android.sample.kotlin_compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.customer.android.sample.kotlin_compose.navigation.AppNavGraph
import io.customer.android.sample.kotlin_compose.ui.login.AuthenticationViewModel
import io.customer.android.sample.kotlin_compose.ui.theme.CustomerIoSDKTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authenticationViewModel: AuthenticationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CustomerIoSDKTheme {
                val navController = rememberNavController()

                val navBackStackEntry by navController.currentBackStackEntryAsState()

                val currentRoute = navBackStackEntry?.destination?.route
                    ?: authenticationViewModel.getDestination()

                AppNavGraph(
                    startDestination = currentRoute,
                    modifier = Modifier.systemBarsPadding()
                )
            }
        }
    }
}
