package io.customer.android.sample.kotlin_compose.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.customer.android.sample.kotlin_compose.R

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
            LoginSettings(onSettingsClick)
            LoginScreenHeader()
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

@Composable
fun ColumnScope.LoginSettings(onSettingsClick: () -> Unit) {
    Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = stringResource(R.string.settings),
        modifier = Modifier
            .size(72.dp)
            .align(Alignment.End)
            .padding(16.dp)
            .clickable(onClick = onSettingsClick)
    )
}

@Composable
fun LoginScreenHeader() {
    Text(
        text = stringResource(R.string.ami_app),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 24.dp)
    )
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
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { newValue -> name = newValue },
            label = { Text(stringResource(R.string.name)) },
            modifier = Modifier.fillMaxWidth(),
            isError = nameErrorState.isNotEmpty(),
            supportingText = { Text(text = nameErrorState) }
        )

        OutlinedTextField(
            value = email,
            onValueChange = { newValue -> email = newValue },
            label = { Text(stringResource(R.string.email)) },
            modifier = Modifier.fillMaxWidth(),
            isError = emailErrorState.isNotEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            supportingText = { Text(text = emailErrorState) }
        )

        Button(
            onClick = {
                onLogin.invoke(email, name)
            },
            shape = RoundedCornerShape(100.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.login),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
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

@Composable
fun BoxScope.VersionText() {
    Text(
        text = "Versions will be displayed here",
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
    )
}

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
fun LoginScreenPreview() {
    LoginScreen(LoginUiState(), onLogin = { _, _ -> }, onSettingsClick = {}, onGuestLogin = {})
}
