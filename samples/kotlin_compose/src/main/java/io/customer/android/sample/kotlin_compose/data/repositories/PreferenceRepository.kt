package io.customer.android.sample.kotlin_compose.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.android.sample.kotlin_compose.data.models.Configuration
import io.customer.android.sample.kotlin_compose.util.CustomerIOSDKConstants
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.API_HOST_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.CDN_HOST_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.CDP_API_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.DEBUG_MODE
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.FLUSH_AT
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.FLUSH_INTERVAL
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.SITE_ID
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_DEVICE_ATTRIBUTES
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_SCREEN
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
            preferences[CDP_API_KEY] = configuration.cdpApiKey
            preferences[SITE_ID] = configuration.siteId
            configuration.apiHost?.let { preferences[API_HOST_KEY] = it }
            configuration.cdnHost?.let { preferences[CDN_HOST_KEY] = it }
            preferences[FLUSH_INTERVAL] = configuration.flushInterval
            preferences[FLUSH_AT] = configuration.flushAt
            preferences[TRACK_SCREEN] = configuration.trackScreen
            preferences[TRACK_DEVICE_ATTRIBUTES] = configuration.trackDeviceAttributes
            preferences[DEBUG_MODE] = configuration.debugMode
        }
    }

    override fun getConfiguration(): Flow<Configuration> {
        return dataStore.data.map { preferences ->
            return@map Configuration(
                cdpApiKey = preferences[CDP_API_KEY] ?: BuildConfig.CDP_API_KEY,
                siteId = preferences[SITE_ID] ?: BuildConfig.SITE_ID
            ).apply {
                apiHost =
                    preferences[API_HOST_KEY]?.takeIf { it.isNotBlank() } ?: CustomerIOSDKConstants.DEFAULT_API_HOST
                cdnHost =
                    preferences[CDN_HOST_KEY]?.takeIf { it.isNotBlank() } ?: CustomerIOSDKConstants.DEFAULT_CDN_HOST

                flushInterval = preferences[FLUSH_INTERVAL] ?: CustomerIOSDKConstants.FLUSH_INTERVAL

                flushAt = preferences[FLUSH_AT] ?: CustomerIOSDKConstants.FLUSH_AT

                trackScreen = preferences[TRACK_SCREEN] ?: CustomerIOSDKConstants.AUTO_TRACK_SCREEN_VIEWS

                trackDeviceAttributes = preferences[TRACK_DEVICE_ATTRIBUTES] ?: CustomerIOSDKConstants.AUTO_TRACK_DEVICE_ATTRIBUTES

                debugMode = preferences[DEBUG_MODE] ?: true
            }
        }
    }

    override suspend fun restoreDefaults(): Configuration {
        val configuration = Configuration(
            cdpApiKey = BuildConfig.CDP_API_KEY,
            siteId = BuildConfig.SITE_ID
        ).apply {
            apiHost = CustomerIOSDKConstants.DEFAULT_API_HOST
            cdnHost = CustomerIOSDKConstants.DEFAULT_CDN_HOST
            flushInterval = CustomerIOSDKConstants.FLUSH_INTERVAL
            flushAt = CustomerIOSDKConstants.FLUSH_AT
            trackScreen = CustomerIOSDKConstants.AUTO_TRACK_SCREEN_VIEWS
            trackDeviceAttributes = CustomerIOSDKConstants.AUTO_TRACK_DEVICE_ATTRIBUTES
            debugMode = true
        }
        saveConfiguration(configuration)
        return configuration
    }
}
