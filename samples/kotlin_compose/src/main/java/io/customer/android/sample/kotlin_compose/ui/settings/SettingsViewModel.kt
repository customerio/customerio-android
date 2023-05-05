package io.customer.android.sample.kotlin_compose.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.customer.android.sample.kotlin_compose.data.models.Configuration
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.sdk.CustomerIO
import io.customer.sdk.util.CioLogLevel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val configuration: Configuration = Configuration("", ""),
    val deviceToken: String = ""
)

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

    fun saveAndUpdateConfiguration(
        configuration: Configuration,
        application: Application,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            preferenceRepository.saveConfiguration(configuration)

            CustomerIO.Builder(
                siteId = configuration.siteId,
                apiKey = configuration.apiKey,
                appContext = application
            ).apply {
                setTrackingApiURL(trackingApiUrl = configuration.trackUrl)
                setBackgroundQueueSecondsDelay(configuration.backgroundQueueSecondsDelay)
                setBackgroundQueueMinNumberOfTasks(configuration.backgroundQueueMinNumTasks)
                if (configuration.debugMode) {
                    setLogLevel(CioLogLevel.DEBUG)
                } else {
                    setLogLevel(CioLogLevel.ERROR)
                }
                autoTrackDeviceAttributes(configuration.trackDeviceAttributes)
                autoTrackScreenViews(configuration.trackScreen)
            }.build()

            onComplete.invoke()
        }
    }
}
