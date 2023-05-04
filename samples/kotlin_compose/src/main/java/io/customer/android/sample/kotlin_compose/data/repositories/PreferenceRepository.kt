package io.customer.android.sample.kotlin_compose.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.customer.android.sample.kotlin_compose.BuildConfig
import io.customer.android.sample.kotlin_compose.data.models.Workspace
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.API_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.SITE_ID
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_API_URL_KEY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PreferenceRepository {
    suspend fun saveWorkspaceCredentials(siteId: String, apiKey: String)
    fun getWorkspaceCredentials(): Flow<Workspace>
    suspend fun saveTrackApiUrl(trackingApiUrl: String)
    fun getTrackApiUrl(): Flow<String?>
}

class PreferenceRepositoryImp(private val dataStore: DataStore<Preferences>) :
    PreferenceRepository {

    override suspend fun saveWorkspaceCredentials(siteId: String, apiKey: String) {
        dataStore.edit { preferences ->
            preferences[SITE_ID] = siteId
            preferences[API_KEY] = apiKey
        }
    }

    override suspend fun saveTrackApiUrl(trackingApiUrl: String) {
        dataStore.edit { preferences ->
            preferences[TRACK_API_URL_KEY] = trackingApiUrl
        }
    }

    override fun getTrackApiUrl(): Flow<String?> {
        return dataStore.data.map { preference ->
            preference[TRACK_API_URL_KEY]
        }
    }

    override fun getWorkspaceCredentials(): Flow<Workspace> {
        return dataStore.data.map { preferences ->
            return@map Workspace(
                siteId = preferences[SITE_ID] ?: BuildConfig.SITE_ID,
                apiKey = preferences[API_KEY] ?: BuildConfig.API_KEY
            )
        }
    }
}
