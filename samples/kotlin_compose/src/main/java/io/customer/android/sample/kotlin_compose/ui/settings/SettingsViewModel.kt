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
    val configuration: Configuration = Configuration("", "", ""),
    val customAPIHostError: String = "",
    val customCDNHostError: String = ""
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
            val apiHostError = validateCustomTrackUrl(configuration.apiHost)
            val cdnHostError = validateCustomTrackUrl(configuration.cdnHost)
            _uiState.emit(
                _uiState.value.copy(
                    configuration = configuration,
                    customAPIHostError = apiHostError,
                    customCDNHostError = cdnHostError
                )
            )
            onComplete.invoke()
        }
    }

    private fun validateCustomTrackUrl(trackUrl: String?): String {
        // Null check
        if (trackUrl.isNullOrBlank()) {
            return ""
        }

        return runCatching {
            val uri = Uri.parse(trackUrl)
            // Since SDK does not support custom schemes, we manually append http:// to the URL
            // So the URL is considered invalid if it ends with a slash, contains a scheme, query or fragment
            return@runCatching when {
                uri.scheme != null -> "URL should not include 'http://' or 'https://'."
                uri.query != null || uri.fragment != null -> "URL should not contain query parameters or fragments"
                // Ending character validation
                trackUrl.endsWith("/") -> "URL must end with '/'"
                // Passed all checks, return empty string
                else -> ""
            }
        }.getOrNull() ?: "Unable to parse URL. Please enter a valid URL."
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
        return validateCustomTrackUrl(configuration.apiHost).isEmpty() &&
            validateCustomTrackUrl(configuration.cdnHost).isEmpty()
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            val config = preferenceRepository.restoreDefaults()
            _uiState.emit(_uiState.value.copy(configuration = config))
        }
    }
}
