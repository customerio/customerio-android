package io.customer.android.sample.kotlin_compose.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
    onTrackCustomEvent: () -> Unit,
    onTrackCustomAttribute: (type: String) -> Unit,
    onLogout: () -> Unit,
    onSettingsClick: () -> Unit
) {
}
