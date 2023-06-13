package io.customer.android.sample.kotlin_compose.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.android.sample.kotlin_compose.data.models.Configuration
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.API_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.BACKGROUND_QUEUE_MIN_NUM_TASKS
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.BACKGROUND_QUEUE_SECONDS_DELAY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.DEBUG_MODE
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.SITE_ID
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_API_URL_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_DEVICE_ATTRIBUTES
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_SCREEN
import io.customer.sdk.CustomerIOConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PreferenceRepository {
    suspend fun saveConfiguration(configuration: Configuration)
    fun getConfiguration(): Flow<Configuration>
    suspend fun restoreDefaults(): Configuration
}

class PreferenceRepositoryImp(private val dataStore: DataStore<Preferences>) :
    PreferenceRepository {

    override suspend fun saveConfiguration(configuration: Configuration) {
        dataStore.edit { preferences ->
            preferences[SITE_ID] = configuration.siteId
            preferences[API_KEY] = configuration.apiKey
            configuration.trackUrl?.let { preferences[TRACK_API_URL_KEY] = it }
            preferences[BACKGROUND_QUEUE_SECONDS_DELAY] = configuration.backgroundQueueSecondsDelay
            preferences[BACKGROUND_QUEUE_MIN_NUM_TASKS] = configuration.backgroundQueueMinNumTasks
            preferences[TRACK_SCREEN] = configuration.trackScreen
            preferences[TRACK_DEVICE_ATTRIBUTES] = configuration.trackDeviceAttributes
            preferences[DEBUG_MODE] = configuration.debugMode
        }
    }

    override fun getConfiguration(): Flow<Configuration> {
        return dataStore.data.map { preferences ->
            return@map Configuration(
                siteId = preferences[SITE_ID] ?: BuildConfig.SITE_ID,
                apiKey = preferences[API_KEY] ?: BuildConfig.API_KEY
            ).apply {
                trackUrl =
                    preferences[TRACK_API_URL_KEY]?.ifEmpty { "https://track-sdk.customer.io/" }
                        ?: "https://track-sdk.customer.io/"

                backgroundQueueSecondsDelay = preferences[BACKGROUND_QUEUE_SECONDS_DELAY]
                    ?: CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY

                backgroundQueueMinNumTasks = preferences[BACKGROUND_QUEUE_MIN_NUM_TASKS]
                    ?: CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS

                trackScreen = preferences[TRACK_SCREEN] ?: true

                trackDeviceAttributes = preferences[TRACK_DEVICE_ATTRIBUTES] ?: true

                debugMode = preferences[DEBUG_MODE] ?: true
            }
        }
    }

    override suspend fun restoreDefaults(): Configuration {
        val configuration = Configuration(
            siteId = BuildConfig.SITE_ID,
            apiKey = BuildConfig.API_KEY
        ).apply {
            trackUrl = "https://track-sdk.customer.io/"
            backgroundQueueSecondsDelay =
                CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY
            backgroundQueueMinNumTasks =
                CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS
            trackScreen = true
            trackDeviceAttributes = true
            debugMode = true
        }
        saveConfiguration(configuration)
        return configuration
    }
}
