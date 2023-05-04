package io.customer.android.sample.kotlin_compose.ui.login

import android.util.Patterns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.R
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.sdk.CustomerIO
import java.util.UUID
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
    private val userRepository: UserRepository,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    // UI state exposed to the UI
    private val _uiState = MutableStateFlow(LoginUiState(loading = true))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loginUser(email: String, name: String, onLoginSuccess: () -> Unit) {
        if (name.isEmpty()) {
            _uiState.update {
                it.copy(
                    nameError = R.string.invalid_name,
                    emailError = null
                )
            }
        } else if (email.isEmpty() || Patterns.EMAIL_ADDRESS.matcher(email).matches().not()) {
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
            CustomerIO.instance().identify(
                identifier = email,
                attributes = mapOf("name" to name, "is_guest" to isGuest)
            )
            CustomerIO.instance().track(
                name = "login",
                attributes = mapOf("name" to name, "email" to email)
            )
            withContext(Dispatchers.Main) {
                onLoginSuccess.invoke()
            }
        }
    }

    fun loginAsGuest(onLoginSuccess: () -> Unit) {
        val uuid = UUID.randomUUID().toString()
        login(
            email = uuid,
            name = "Guest",
            isGuest = true,
            onLoginSuccess = onLoginSuccess
        )
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
