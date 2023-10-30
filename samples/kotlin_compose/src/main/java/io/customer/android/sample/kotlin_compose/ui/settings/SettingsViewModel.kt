package io.customer.android.sample.kotlin_compose.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.data.models.Configuration
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.sdk.CustomerIO
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val configuration: Configuration = Configuration("", ""),
    val customTrackUrlError: String = ""
) {
    val deviceToken: String by lazy { CustomerIO.instance().registeredDeviceToken ?: "" }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    private val _uiState: MutableStateFlow<SettingsUiState> = MutableStateFlow(SettingsUiState())
    val uiState = _uiState

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            preferenceRepository.getConfiguration().collect {
                _uiState.emit(_uiState.value.copy(configuration = it))
            }
        }
    }

    fun updateConfiguration(
        configuration: Configuration,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val trackUrlValidation = validateCustomTrackUrl(configuration.trackUrl)
            _uiState.emit(
                _uiState.value.copy(
                    configuration = configuration,
                    customTrackUrlError = trackUrlValidation
                )
            )
            onComplete.invoke()
        }
    }

    private fun validateCustomTrackUrl(trackUrl: String?): String {
        // Null check
        if (trackUrl == null) {
            return ""
        }
        // Protocol validation
        if (!trackUrl.startsWith("http://") && !trackUrl.startsWith("https://")) {
            return "URL must start with 'http://' or 'https://'"
        }
        // Host validation
        if (Uri.parse(trackUrl)?.authority.isNullOrBlank()) {
            return "Host must be defined"
        }
        // Ending character validation
        if (!trackUrl.endsWith("/")) {
            return "URL must end with '/'"
        }
        // Passed all checks, return empty string
        return ""
    }

    fun saveAndUpdateConfiguration(
        configuration: Configuration,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            if (validateConfiguration(configuration)) {
                preferenceRepository.saveConfiguration(configuration)
                onComplete.invoke()
            }
        }
    }

    private fun validateConfiguration(configuration: Configuration): Boolean {
        return validateCustomTrackUrl(configuration.trackUrl).isEmpty()
    }

    fun restoreDefaults(onComplete: () -> Unit) {
        viewModelScope.launch {
            val config = preferenceRepository.restoreDefaults()
            _uiState.emit(_uiState.value.copy(configuration = config))
            onComplete.invoke()
        }
    }
}
