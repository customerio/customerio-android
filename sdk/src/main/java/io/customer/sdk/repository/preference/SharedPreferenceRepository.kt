package io.customer.sdk.repository.preference

import android.content.Context
import io.customer.sdk.extensions.toRegion
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
            putString(ORGANIZATION_ID, it.organizationId)
            putString(TRACKING_API_URL, it.trackingApiUrl)
            putBoolean(AUTO_TRACK_DEVICE_ATTRIBUTES, it.autoTrackDeviceAttributes)
            putInt(LOG_LEVEL, it.logLevel.ordinal)
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
                organizationId = getString(ORGANIZATION_ID, null),
                trackingApiUrl = getString(TRACKING_API_URL, null),
                autoTrackDeviceAttributes = getBoolean(AUTO_TRACK_DEVICE_ATTRIBUTES, true),
                logLevel = CioLogLevel.values()
                    .getOrElse(getInt(LOG_LEVEL, -1)) { CioLogLevel.ERROR },
                backgroundQueueMinNumberOfTasks = getInt(BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS, 10),
                backgroundQueueSecondsDelay = getFloat(
                    BACKGROUND_QUEUE_SECONDS_DELAY,
                    30F
                ).toDouble()
            )
        }
    }

    companion object {
        const val SITE_ID = "siteId"
        const val API_KEY = "apiKey"
        const val REGION = "region"
        const val ORGANIZATION_ID = "organizationId"

        const val TRACKING_API_URL = "trackingApiUrl"
        const val AUTO_TRACK_DEVICE_ATTRIBUTES = "autoTrackDeviceAttributes"
        const val LOG_LEVEL = "logLevel"
        const val BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS = "backgroundQueueMinNumberOfTasks"
        const val BACKGROUND_QUEUE_SECONDS_DELAY = "backgroundQueueSecondsDelay"
    }
}
