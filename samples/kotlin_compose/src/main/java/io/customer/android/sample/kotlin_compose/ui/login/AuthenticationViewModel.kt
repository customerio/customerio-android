package io.customer.android.sample.kotlin_compose.ui.login

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.data.repositories.UserRepository
import io.customer.android.sample.kotlin_compose.navigation.Screen
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@HiltViewModel
class AuthenticationViewModel @Inject constructor(
    private val userRepository: UserRepository
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
