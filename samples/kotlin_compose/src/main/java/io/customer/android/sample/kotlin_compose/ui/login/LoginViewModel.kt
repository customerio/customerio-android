package io.customer.android.sample.kotlin_compose.ui.login

import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.sdk.CustomerIO
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the Login screen
 */
data class LoginUiState(
    @StringRes val emailError: Int? = null,
    @StringRes val nameError: Int? = null,
    val loading: Boolean = false,
    val user: User? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    // UI state exposed to the UI
    private val _uiState = MutableStateFlow(LoginUiState(loading = true))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loginUser(email: String, name: String, onLoginSuccess: () -> Unit) {
        if (email.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(email).matches().not()) {
            _uiState.update {
                it.copy(
                    emailError = R.string.invalid_email,
                    nameError = null
                )
            }
        } else {
            login(email = email, name = name, onLoginSuccess = onLoginSuccess)
        }
    }

    private fun login(
        email: String,
        name: String,
        isGuest: Boolean = false,
        onLoginSuccess: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            userRepository.login(email = email, name = name, isGuest = isGuest)
            io.customer.sdk.android.CustomerIO.instance().identify(
                userId = email,
                traits = mapOf("name" to name, "is_guest" to isGuest)
            )
            io.customer.sdk.android.CustomerIO.instance().track(
                name = "login",
                properties = mapOf("name" to name, "email" to email)
            )
            withContext(Dispatchers.Main) {
                onLoginSuccess.invoke()
            }
        }
    }

    fun loginAsGuest(onLoginSuccess: () -> Unit) {
        login(
            email = generateRandomEmail(),
            name = "",
            isGuest = true,
            onLoginSuccess = onLoginSuccess
        )
    }

    private fun generateRandomEmail(): String {
        val randomString = (1..10).map {
            (('a'..'z') + ('A'..'Z') + ('0'..'9')).random()
        }.joinToString("")

        return "$randomString@customer.io"
    }

    private fun loadData() {
        _uiState.update { it.copy(loading = true) }

        viewModelScope.launch {
            userRepository.getUser().collect { user ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        emailError = null,
                        nameError = null,
                        user = user
                    )
                }
            }
        }
    }
}
