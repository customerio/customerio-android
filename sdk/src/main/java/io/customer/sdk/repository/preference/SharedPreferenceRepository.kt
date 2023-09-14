package io.customer.sdk.repository.preference

import android.content.Context
import androidx.annotation.VisibleForTesting
import io.customer.sdk.Version
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.util.CioLogLevel

@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
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
            putString(CLIENT_SOURCE, it.client.source)
            putString(CLIENT_SDK_VERSION, it.client.sdkVersion)
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
                region = Region.getRegion(getString(REGION, null)),
                client = Client.fromRawValue(
                    source = getString(CLIENT_SOURCE, null) ?: "Unknown",
                    sdkVersion = getString(CLIENT_SDK_VERSION, null) ?: Version.version
                ),
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
        const val CLIENT_SOURCE = "clientSource"
        const val CLIENT_SDK_VERSION = "clientSdkVersion"

        const val TRACKING_API_URL = "trackingApiUrl"
        const val AUTO_TRACK_DEVICE_ATTRIBUTES = "autoTrackDeviceAttributes"
        const val LOG_LEVEL = "logLevel"
        const val BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS = "backgroundQueueMinNumberOfTasks"
        const val BACKGROUND_QUEUE_SECONDS_DELAY = "backgroundQueueSecondsDelay"
    }
}
