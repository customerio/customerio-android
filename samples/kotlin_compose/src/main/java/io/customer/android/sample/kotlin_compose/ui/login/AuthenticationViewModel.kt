package io.customer.android.sample.kotlin_compose.ui.login

import androidx.lifecycle.ViewModel
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.di.ServiceLocator
import io.customer.android.sample.kotlin_compose.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class AuthenticationViewModel(
    private val userRepository: UserRepository = ServiceLocator.userRepository
) : ViewModel() {

    fun getDestination(): String {
        val isLoggedIn = runBlocking(Dispatchers.IO) {
            userRepository.isLoggedIn()
        }
        return if (isLoggedIn) {
            Screen.Dashboard.route
        } else {
            Screen.Login.route
        }
    }
}
