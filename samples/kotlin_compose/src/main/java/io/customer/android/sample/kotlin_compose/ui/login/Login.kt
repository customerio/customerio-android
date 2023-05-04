package io.customer.android.sample.kotlin_compose.ui.login

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginRoute(
    loginViewModel: LoginViewModel = hiltViewModel(),
    onSettingsClick: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    Text(text = "Login")
}
