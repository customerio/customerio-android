package io.customer.android.sample.kotlin_compose

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.API_KEY
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.SITE_ID
import io.customer.android.sample.kotlin_compose.util.PreferencesKeys.TRACK_API_URL_KEY
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
import io.customer.sdk.util.CioLogLevel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainApplication : Application() {

    @Inject
    lateinit var dataStore: DataStore<Preferences>
    override fun onCreate() {
        super.onCreate()
        val (trackingApiUrl, siteId, apiKey) = runBlocking {
            return@runBlocking try {
                listOf(
                    dataStore.data.first()[TRACK_API_URL_KEY],
                    dataStore.data.first()[SITE_ID],
                    dataStore.data.first()[API_KEY]
                )
            } catch (e: Exception) {
                listOf(null, BuildConfig.SITE_ID, BuildConfig.API_KEY)
            }
        }

        CustomerIO.Builder(
            siteId = siteId ?: BuildConfig.SITE_ID,
            apiKey = apiKey ?: BuildConfig.API_KEY,
            appContext = this
        ).apply {
            trackingApiUrl?.let { setTrackingApiURL(trackingApiUrl = it) }
            addCustomerIOModule(ModuleMessagingInApp())
            addCustomerIOModule(ModuleMessagingPushFCM())
            setLogLevel(CioLogLevel.DEBUG)
            build()
        }
    }
}
