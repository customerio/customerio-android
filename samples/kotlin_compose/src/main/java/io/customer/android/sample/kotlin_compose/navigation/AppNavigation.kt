package io.customer.android.sample.kotlin_compose.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import io.customer.android.sample.kotlin_compose.navigation.Screen.CustomAttribute.ARGS_TRACKING_EVENT_TYPE
import io.customer.android.sample.kotlin_compose.ui.customTrack.CustomAttributeRoute
import io.customer.android.sample.kotlin_compose.ui.customTrack.CustomEventRoute
import io.customer.android.sample.kotlin_compose.ui.dashboard.DashboardRoute
import io.customer.android.sample.kotlin_compose.ui.login.LoginRoute
import io.customer.android.sample.kotlin_compose.ui.settings.SettingsRoute

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Settings : Screen("settings")
    object Dashboard : Screen("dashboard")
    object CustomEvent : Screen("custom_event_track")
    object CustomAttribute : Screen("custom_attribute/{type}") {
        const val ARGS_TRACKING_EVENT_TYPE = "type"
        const val TYPE_PROFILE = "profile"
        const val TYPE_DEVICE = "device"

        fun createRoute(type: String): String {
            return "custom_attribute/$type"
        }
    }
}

@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Login.route
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        addLoginRoute(navController)
        addSettingsRoute(navController)
        addDashboardRoute(navController)
        addCustomEventRoute(navController)
        addCustomAttributeRoute(navController)
    }
}

internal fun NavGraphBuilder.addCustomAttributeRoute(
    navController: NavHostController
) {
    composable(
        route = Screen.CustomAttribute.route,
        arguments = listOf(navArgument(ARGS_TRACKING_EVENT_TYPE) { type = NavType.StringType })
    ) {
        val trackingType = it.arguments?.getString(ARGS_TRACKING_EVENT_TYPE)
        // types of attribute could be profile and device
        requireNotNull(trackingType) { "$ARGS_TRACKING_EVENT_TYPE parameter wasn't found. Please make sure it's set!" }
        CustomAttributeRoute(attributeType = trackingType, onBackPressed = {
            navController.navigateUp()
        })
    }
}

internal fun NavGraphBuilder.addLoginRoute(
    navController: NavHostController
) {
    composable(Screen.Login.route) {
        LoginRoute(onLoginSuccess = {
            navController.navigate(Screen.Dashboard.route) {
                launchSingleTop = true
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }, onSettingsClick = {
            navController.navigate(Screen.Settings.route)
        })
    }
}

internal fun NavGraphBuilder.addSettingsRoute(
    navController: NavHostController
) {
    composable(
        route = Screen.Settings.route,
        deepLinks = (
            listOf(
                navDeepLink {
                    uriPattern = "kotlin-sample://settings"
                },
                navDeepLink {
                    uriPattern = "https://www.kotlin-sample.com/settings"
                }
            )
            )
    ) {
        SettingsRoute(onBackPressed = {
            navController.navigateUp()
        })
    }
}

internal fun NavGraphBuilder.addCustomEventRoute(
    navController: NavHostController
) {
    composable(Screen.CustomEvent.route) {
        CustomEventRoute(onBackPressed = {
            navController.navigateUp()
        })
    }
}

internal fun NavGraphBuilder.addDashboardRoute(
    navController: NavHostController
) {
    composable(Screen.Dashboard.route) {
        DashboardRoute(onTrackCustomEvent = {
            navController.navigate(Screen.CustomEvent.route)
        }, onTrackCustomAttribute = {
            navController.navigate(Screen.CustomAttribute.createRoute(it))
        }, onSettingsClick = {
            navController.navigate(Screen.Settings.route)
        }, onLogout = {
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.Dashboard.route) { inclusive = true }
            }
        })
    }
}
