package io.customer.android.sample.kotlin_compose.util

import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val SITE_ID = stringPreferencesKey("siteId")
    val API_KEY = stringPreferencesKey("apiKey")
    val TRACK_API_URL_KEY = stringPreferencesKey("trackApiUrlKey")
}
