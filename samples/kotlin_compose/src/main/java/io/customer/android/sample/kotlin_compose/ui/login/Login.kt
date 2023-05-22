package io.customer.android.sample.kotlin_compose.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.ui.components.ActionButton
import io.customer.android.sample.kotlin_compose.ui.components.HeaderText
import io.customer.android.sample.kotlin_compose.ui.components.SettingsIcon
import io.customer.android.sample.kotlin_compose.ui.components.VersionText

@Composable
fun LoginRoute(
    loginViewModel: LoginViewModel = hiltViewModel(),
    onSettingsClick: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val state = loginViewModel.uiState.collectAsState()

    LoginScreen(
        uiState = state.value,
        onLogin = { email, name ->
            loginViewModel.loginUser(
                email = email,
                name = name,
                onLoginSuccess = onLoginSuccess
            )
        },
        onGuestLogin = {
            loginViewModel.loginAsGuest(onLoginSuccess)
        },
        onSettingsClick = onSettingsClick
    )
}

@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onLogin: (email: String, name: String) -> Unit,
    onSettingsClick: () -> Unit,
    onGuestLogin: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsIcon(onSettingsClick)
            HeaderText(stringResource(R.string.ami_app))
            LoginFieldsView(
                emailErrorState = if (uiState.emailError == null) "" else stringResource(id = uiState.emailError),
                nameErrorState = if (uiState.nameError == null) "" else stringResource(id = uiState.nameError),
                onLogin = onLogin,
                onGuestLogin = onGuestLogin
            )
        }
        VersionText()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginFieldsView(
    emailErrorState: String,
    nameErrorState: String,
    onLogin: (email: String, name: String) -> Unit,
    onGuestLogin: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { newValue -> name = newValue },
            label = { Text(stringResource(R.string.name)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("name")
                .padding(start = 0.dp, end = 0.dp),
            isError = nameErrorState.isNotEmpty(),
            supportingText = { Text(text = nameErrorState) }
        )

        OutlinedTextField(
            value = email,
            onValueChange = { newValue -> email = newValue },
            label = { Text(stringResource(R.string.email)) },
            modifier = Modifier
                .testTag("email")
                .fillMaxWidth()
                .padding(start = 0.dp, end = 0.dp),
            isError = emailErrorState.isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            supportingText = { Text(text = emailErrorState) }
        )

        ActionButton(
            text = stringResource(R.string.login),
            modifier = Modifier.testTag("login"),
            onClick = {
                onLogin.invoke(email, name)
            }
        )

        Text(
            text = stringResource(R.string.continue_as_guest),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(bottom = 28.dp)
                .clickable {
                    onGuestLogin.invoke()
                }
        )
    }
}

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
fun LoginScreenPreview() {
    LoginScreen(LoginUiState(), onLogin = { _, _ -> }, onSettingsClick = {}, onGuestLogin = {})
}
