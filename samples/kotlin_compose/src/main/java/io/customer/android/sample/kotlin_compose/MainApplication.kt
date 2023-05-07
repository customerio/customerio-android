package io.customer.android.sample.kotlin_compose

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.customer.android.sample.kotlin_compose.data.repositories.PreferenceRepository
import io.customer.messaginginapp.ModuleMessagingInApp
import io.customer.messagingpush.ModuleMessagingPushFCM
import io.customer.sdk.CustomerIO
import io.customer.sdk.util.CioLogLevel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var preferences: PreferenceRepository

    override fun onCreate() {
        super.onCreate()
        val configuration = runBlocking {
            preferences.getConfiguration().first()
        }

        CustomerIO.Builder(
            siteId = configuration.siteId,
            apiKey = configuration.apiKey,
            appContext = this
        ).apply {
            configuration.trackUrl?.let {
                setTrackingApiURL(trackingApiUrl = it)
            }
            setBackgroundQueueSecondsDelay(configuration.backgroundQueueSecondsDelay)
            setBackgroundQueueMinNumberOfTasks(configuration.backgroundQueueMinNumTasks)
            if (configuration.debugMode) {
                setLogLevel(CioLogLevel.DEBUG)
            } else {
                setLogLevel(CioLogLevel.ERROR)
            }
            autoTrackDeviceAttributes(configuration.trackDeviceAttributes)
            autoTrackScreenViews(configuration.trackScreen)

            addCustomerIOModule(ModuleMessagingInApp())
            addCustomerIOModule(ModuleMessagingPushFCM())

            build()
        }
    }
}
