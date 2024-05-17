package io.customer.android.sample.kotlin_compose.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val CDP_API_KEY = stringPreferencesKey("cdpApiKey")
    val SITE_ID = stringPreferencesKey("siteId")

    val API_HOST_KEY = stringPreferencesKey("apiHostKey")
    val CDN_HOST_KEY = stringPreferencesKey("cdnHostKey")

    val FLUSH_INTERVAL = intPreferencesKey("flushInterval")
    val FLUSH_AT = intPreferencesKey("flushAt")

    val TRACK_SCREEN = booleanPreferencesKey("trackScreen")
    val TRACK_DEVICE_ATTRIBUTES = booleanPreferencesKey("trackDeviceAttributes")
    val DEBUG_MODE = booleanPreferencesKey("debugMode")
}
