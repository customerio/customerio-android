package io.customer.android.sample.kotlin_compose.util

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object PreferencesKeys {
    val CDP_API_KEY = stringPreferencesKey("cdpApiKey")
    val SITE_ID = stringPreferencesKey("siteId")
    val API_KEY = stringPreferencesKey("apiKey")

    val TRACK_API_URL_KEY = stringPreferencesKey("trackApiUrlKey")

    val BACKGROUND_QUEUE_SECONDS_DELAY = doublePreferencesKey("backgroundQueueSecondsDelay")
    val BACKGROUND_QUEUE_MIN_NUM_TASKS = intPreferencesKey("backgroundQueueMinNumTasks")

    val TRACK_SCREEN = booleanPreferencesKey("trackScreen")
    val TRACK_DEVICE_ATTRIBUTES = booleanPreferencesKey("trackDeviceAttributes")
    val DEBUG_MODE = booleanPreferencesKey("debugMode")
}
