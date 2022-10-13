package io.customer.sdk.repository.preference

import android.content.Context
import io.customer.sdk.extensions.toRegion
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.util.CioLogLevel

interface SharedPreferenceRepository {

    fun saveSettings(values: CustomerIOStoredValues)

    fun loadSettings(): CustomerIOStoredValues
}

internal class SharedPreferenceRepositoryImp(context: Context) : SharedPreferenceRepository,
    BasePreferenceRepository(context) {

    override val prefsName: String by lazy {
        "io.customer.sdk.${context.packageName}.PREFERENCE_FILE_KEY"
    }

    override fun saveSettings(
        values: CustomerIOStoredValues
    ) = with(prefs.edit()) {
        values.also {
            putString(SITE_ID, it.siteId)
            putString(API_KEY, it.apiKey)
            putString(REGION, it.region.code)
            putString(TRACKING_API_URL, it.trackingApiUrl)
            putBoolean(AUTO_TRACK_DEVICE_ATTRIBUTES, it.autoTrackDeviceAttributes)
            putString(LOG_LEVEL, it.logLevel.name)
            putInt(BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS, it.backgroundQueueMinNumberOfTasks)
            putFloat(BACKGROUND_QUEUE_SECONDS_DELAY, it.backgroundQueueSecondsDelay.toFloat())
        }
        apply()
    }

    override fun loadSettings(): CustomerIOStoredValues {
        return with(prefs) {
            CustomerIOStoredValues(
                siteId = getString(SITE_ID, null).orEmpty(),
                apiKey = getString(API_KEY, null).orEmpty(),
                region = getString(REGION, null).toRegion(),
                trackingApiUrl = getString(TRACKING_API_URL, null),
                autoTrackDeviceAttributes = getBoolean(AUTO_TRACK_DEVICE_ATTRIBUTES, true),
                logLevel = getString(LOG_LEVEL, null)?.let { valueOfOrNull<CioLogLevel>(it) }
                    ?: CioLogLevel.ERROR,
                backgroundQueueMinNumberOfTasks = getInt(BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS, 10),
                backgroundQueueSecondsDelay = getFloat(
                    BACKGROUND_QUEUE_SECONDS_DELAY,
                    30F
                ).toDouble()
            )
        }
    }

    private companion object {
        const val SITE_ID = "siteId"
        const val API_KEY = "apiKey"
        const val REGION = "region"

        const val TRACKING_API_URL = "trackingApiUrl"
        const val AUTO_TRACK_DEVICE_ATTRIBUTES = "autoTrackDeviceAttributes"
        const val LOG_LEVEL = "logLevel"
        const val BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS = "backgroundQueueMinNumberOfTasks"
        const val BACKGROUND_QUEUE_SECONDS_DELAY = "backgroundQueueSecondsDelay"
    }
}
