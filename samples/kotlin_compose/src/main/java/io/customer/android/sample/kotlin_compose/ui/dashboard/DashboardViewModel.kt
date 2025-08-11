package io.customer.android.sample.kotlin_compose.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.customer.android.sample.kotlin_compose.data.models.User
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.di.ServiceLocator
import io.customer.sdk.CustomerIO
import java.util.Calendar
import java.util.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val userRepository: UserRepository = ServiceLocator.userRepository
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

    fun sendRandomEvent() {
        when (Random().nextInt(3)) {
            0 -> {
                CustomerIO.instance().track("Order Purchased")
            }

            1 -> {
                val attributes = mapOf("movie_name" to "The Incredibles")
                CustomerIO.instance().track("movie_watched", attributes)
            }

            2 -> {
                val sevenDaysLater =
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.time
                val attributes = mapOf("appointmentTime" to sevenDaysLater)
                CustomerIO.instance().track("appointmentScheduled", attributes)
            }
        }
    }
}
