package io.customer.android.sample.kotlin_compose.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.sdk.CustomerIO
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    // UI state exposed to the UI
    private val _uiState = MutableStateFlow(User("", ""))
    val uiState: StateFlow<User> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            userRepository.getUser().collect {
                _uiState.value = it ?: User("", "")
            }
        }
    }

    fun logout(user: User, onLogout: () -> Unit) {
        viewModelScope.launch {
            userRepository.deleteUser(user)
            CustomerIO.instance().clearIdentify()
            onLogout.invoke()
        }
    }
}
