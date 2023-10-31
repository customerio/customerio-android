package io.customer.sdk.repository.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.customer.sdk.Version
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.extensions.valueOfOrNull
import io.customer.sdk.util.CioLogLevel

internal interface SharedPreferenceRepository {
    fun saveSettings(values: CustomerIOStoredValues)

    fun loadSettings(): CustomerIOStoredValues
}

internal class SharedPreferenceRepositoryImp(context: Context) : SharedPreferenceRepository,
    BasePreferenceRepository(context) {

    // The file name used for storing shared preferences.
    // This preference file should not be included in Auto Backup.
    // Upon restoration, the encryption key originally used may no longer be present.
    // To prevent this, ensure the file name matches the one excluded in backup_rules.xml.
    // Read more: https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
    override val prefsName: String by lazy {
        "io.customer.sdk.EncryptedConfigCache"
    }

    override val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
